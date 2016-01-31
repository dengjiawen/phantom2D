package animator.phantom.controller;

/*
    Copyright Janne Liljeblad.

    This file is part of Phantom2D.

    Phantom2D is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Phantom2D is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Phantom2D.  If not, see <http://www.gnu.org/licenses/>.
*/

import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.net.URL;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.MetalTheme;

import animator.phantom.blender.Blender;
import animator.phantom.gui.AnimatorFrame;
import animator.phantom.project.MovieFormat;
import animator.phantom.project.Project;
import animator.phantom.undo.PhantomUndoManager;

//--- Logic and application wide state, including app initializing, window management, opening projects and render aborts.
public class Application implements /*WindowStateListener,*/ WindowListener
{
	//--- There can only be one.
	private static Application app;
	public static Application getApplication(){ return app; }

	//--- Flag to load plugins when opening default project
	private static boolean pluginsLoaded = false;

	//--- Windows
	private AnimatorFrame animatorFrame;

	//--- Render abort management
	public static final int PREVIEW_RENDER = 0;
	public static final int WRITE_RENDER = 1;
	private static int currentRenderType = -1;

	//--- Window params.
	public static int SMALL_WINDOW_WIDTH = 320;

	//--- Project open flag, used to block some updates during project loading.
	private static boolean projectLoading = false;

	//--- Paths
	private static String RESOURCE_PATH = "/res/";
	private static String PERSISTANCE_PATH = RESOURCE_PATH + "persistance/";
	public static String PERSISTANCE_PATH_IN_JAR = "res/persistance/";
	public static String PERSISTANCE_PATH_IN_FILESYSTEM_FOR_JAR = "/phantomeditor.xml";
	private static String LANG_PATH = RESOURCE_PATH + "lang/";
	private static String FORMAT_PATH = RESOURCE_PATH + "format/";
	private static final String FILE_URL_PROTOCOL = "file";
	private static final String JAR_URL_PROTOCOL = "jar";
	private static final String CLASS_PATH_TO_THIS = "animator/phantom/controller/Application.class";
	private static final String JAR_PATH_PART = "/Phantom2D.jar!";

	private static boolean inJar = false;
	private static String filePrefPathForJar;


	public Application(){ app = this; }

	public void startUp()
	{
		System.out.println( "//----------------------- THREADED BRACH -------------------------------//" );

		System.out.println("LD Library Path:" + System.getProperty("java.library.path"));


		//--- Lets find out where we are and set paths.
		ClassLoader loader = getClass().getClassLoader();
		URL urlToThisClass = loader.getResource( CLASS_PATH_TO_THIS );

		if( urlToThisClass.getProtocol().equals( FILE_URL_PROTOCOL ) )
		{
			System.out.println( "App running in file system.");
			
		}
		else if( urlToThisClass.getProtocol().equals( JAR_URL_PROTOCOL ) )
		{
			System.out.println( "App running in jar.");
			inJar = true;
		}
		else
		{
			System.out.println( "This is not running in file system or jar? i iz confused...");
		}

		//--- Get home path and set RESOURCE_PATH, PERSISTANCE_PATH and LANG_PATH, FORMAT_PATH
		//--- if were not running in jar
		String urlPath = urlToThisClass.getPath();
		urlPath = urlPath.substring( 0, urlPath.length() - CLASS_PATH_TO_THIS.length() - 1 );
		if( inJar )
			urlPath = urlPath.substring( 5, urlPath.length() - JAR_PATH_PART.length() );

		String homePath = urlPath;

		if( !inJar )
		{
			RESOURCE_PATH = homePath + RESOURCE_PATH;
			PERSISTANCE_PATH = homePath + PERSISTANCE_PATH;
			LANG_PATH = homePath + LANG_PATH;
			FORMAT_PATH = homePath + FORMAT_PATH;
		}
		System.out.println("app home path:" + homePath );

		//--- Start bringing app up
		AppUtils.printTitle("PHANTOM 2D" );

		//--- Read editor persistance for lang, recent documents, plugin dir, import dir etc...
		if( !inJar )
		{
			EditorPersistance.read( PERSISTANCE_PATH + EditorPersistance.DOC_NAME, false );
		}
		else 
		{
			//--- if running in jar we might have to create prefdoc in file system out side jar
			filePrefPathForJar = homePath + PERSISTANCE_PATH_IN_FILESYSTEM_FOR_JAR;
			File prefTest = new File( filePrefPathForJar );
			if( prefTest.exists() )
			{
				EditorPersistance.read( filePrefPathForJar, false );
			}
			else
			{
				//--- Load default prefs from jar, the write to file system and read again from file system
				EditorPersistance.read( PERSISTANCE_PATH_IN_JAR + EditorPersistance.DOC_NAME, true );
				EditorPersistance.write( filePrefPathForJar );
				EditorPersistance.read( filePrefPathForJar, false );
			}
		}

		MemoryManager.initMemoryManager();

		//--- Theme needs to be initialized before window is created.
		MetalTheme appTheme = new DarkTheme();

		//--- Create window
		animatorFrame = new AnimatorFrame();
		animatorFrame.setVisible( false );
		animatorFrame.addWindowListener( this );

		try {
			MetalLookAndFeel.setCurrentTheme( appTheme );
			UIManager.setLookAndFeel(new MetalLookAndFeel());
			SwingUtilities.updateComponentTreeUI(animatorFrame);

		} catch(Exception e) {
			e.printStackTrace();
			System.out.println("Error setting LAF: " + e);
		}

		//--- There is always a document open.
		openDefaultProject();
		
		//--- Display info window on first run.
		if( EditorPersistance.getBooleanPref( EditorPersistance.FIRST_RUN ) )
		{
			EditorPersistance.setPref( EditorPersistance.FIRST_RUN, false );
			EditorPersistance.write();
		}

		AppUtils.printTitle( "APPLICATION LOADED!" );
	}

	public void openDefaultProject()
	{
		RenderModeController.reset();

		MovieFormat format = MovieFormat.DEFAULT;
		Project project = new Project( "untitled.phr", format );
		openProject( project );
	}
	
	public void openProject( Project project )
	{
		AppUtils.printTitle("OPEN PROJECT " + project.getName() );

		//--- Block cache updates
		projectLoading = true;

		//--- (re-)read editor persistance for recent documents
		if( !inJar )
			EditorPersistance.read( PERSISTANCE_PATH + EditorPersistance.DOC_NAME, false );
		else
			EditorPersistance.read( filePrefPathForJar, false );

		//--- reset some global data.
		GUIComponents.reset();
		TimeLineController.reset();
		PreviewController.reset();

		//--- Set project.
		ProjectController.reset();
		ProjectController.setProject( project );

		//--- Blender
		Blender.initBlenders();

		//--- Editor data.
		IOPLibraryInitializer.init();
		TimeLineController.init();

		//--- Load plugins once IF NOT LOADED
		//--- Here because iops need a loaded project and a initialized Blender to function
		if( !pluginsLoaded )
		{
			PluginController.loadPlugins();
			pluginsLoaded = true;
		}

		//--- Undo
		PhantomUndoManager.init();

		//--- Windows
		animatorFrame.initializeEditor();

		//--- Notify MemoryManager to start guessing
		MemoryManager.calculateCacheSizes();

		//--- 
		animatorFrame.setVisible( true );
		GUIComponents.renderFlowPanel.setIgnoreRepaint( false );// bugs when not visible?

		//--- Display project info
		String info = project.getName() + ",  " + Integer.toString(project.getScreenDimensions().width)
				+ " x " +  Integer.toString(project.getScreenDimensions().height) + ",  "
				+ Integer.toString( project.getLength() ) + " frames";
		GUIComponents.renderFlowButtons.setInfoText( info );

		//--- First render for view editor
		EditorsController.fillViewEditor();
		EditorsController.displayCurrentInViewEditor( false );

		//--- Display loaded clips
		TimeLineController.addClips( project.getLoadClips() );
		TimeLineController.initClipEditorGUI();

		//--- Set cache sizes with current information.
		MemoryManager.initCache();

		//--- Unblock cache and view editor updates.
		projectLoading = false;
	}

 	//--- We're blocking some updates that will fire when project data is created.
	public static boolean isLoading(){ return projectLoading; }
	
	//--- layout calculations need this
	public static int getParamEditHeight()
	{ 
		int SCREEN_HEIGHT = getUsableScreen().height;
		return SCREEN_HEIGHT / 2;
	}

	//--- layout calculations need this
	public static Dimension getUsableScreen()
	{
		return Toolkit.getDefaultToolkit().getScreenSize();
	}

	//--- Pass control to the one who set it's self being doing rendering
	public static void renderAbort()
	{
		System.out.println( "Application.renderAbort()" );
		if( currentRenderType == WRITE_RENDER ) RenderModeController.frameRendererAborted();
	}
	//--- A code initiating rendering sets this when it starts rendering 
	//--- so we can return to correct place after abort.
	public static void setCurrentRenderType( int rType ) { currentRenderType = rType; }

	//-------------------------------------------------- PATH, JAR HANDLING
	public static String getResourcePath(){ return RESOURCE_PATH; }
	public static String getFormatPath(){ return FORMAT_PATH; }
	public static boolean inJar(){ return inJar; }

	//---------------------------------------------- WINDOW EVENTS
	public void windowActivated(WindowEvent e){}
	public void windowClosed(WindowEvent e){}
	public void windowClosing(WindowEvent e)
	{
		MenuActions.quit();//catch for close confirmation
	}
	public void windowDeactivated(WindowEvent e){}
	public void windowDeiconified(WindowEvent e){}
	public void windowIconified(WindowEvent e){}
	public void windowOpened(WindowEvent e) {}

}//end class