package usr.skyswimmer.polyscriptjsonemitter.tools;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asf.cyan.fluid.bytecode.FluidClassPool;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import usr.skyswimmer.polyscriptrunner.PolyScript;
import usr.skyswimmer.polyscriptrunner.PolyScriptEngine;
import usr.skyswimmer.polyscriptrunner.plugins.IPolyscriptPlugin;
import usr.skyswimmer.polyscriptrunner.plugins.PluginScanner;
import usr.skyswimmer.quicktoolsutils.connective.logger.Log4jManagerImpl;

public class PolyJsonEmitter {

	private static Logger logger;

	public static void main(String[] args) throws IOException, ClassNotFoundException {
		// Argument parsing

		// Setup logging
		if (System.getProperty("debugMode") != null) {
			System.setProperty("log4j2.configurationFile",
					PolyScriptEngine.class.getResource("/log4j2-ide.xml").toString());
		} else {
			System.setProperty("log4j2.configurationFile",
					PolyScriptEngine.class.getResource("/log4j2.xml").toString());
		}
		new Log4jManagerImpl().assignAsMain();

		// Script
		if (args.length == 0) {
			System.err.println("Error: missing argument: script file");
			System.exit(1);
			return;
		}
		String scriptF = args[0];
		File scriptFile = new File(scriptF);
		if (!scriptFile.exists() || !scriptFile.isFile()) {
			System.err.println("Error: invalid argument: script file: file does not exist");
			System.exit(1);
			return;
		}

		// Source file
		if (args.length == 1) {
			System.err.println("Error: missing argument: source file");
			System.exit(1);
			return;
		}
		String sourceF = args[1];
		File sourceFile = new File(sourceF);

		// Destination file
		if (args.length == 2) {
			System.err.println("Error: missing argument: destination file");
			System.exit(1);
			return;
		}
		String destF = args[2];
		File destFile = new File(destF);

		// Log
		logger = LogManager.getLogger("polyscript-runner");
		logger.info("Setting up class pool...");

		// Load plugins
		HashMap<String, IPolyscriptPlugin> plugins = new HashMap<String, IPolyscriptPlugin>();
		FluidClassPool pool = FluidClassPool.create();

		// Set up engine
		logger.info("Creating engine...");
		PolyScriptEngine engine = new PolyScriptEngine(scriptFile, name -> plugins.get(name), Level.INFO);
		try {
			// Load plugins
			// Import classpath
			logger.info("Loading plugins...");
			logger.info("Importing classpath...");
			pool.importAllSources();

			// Scan for plugins
			logger.info("Scanning for plugins...");
			PluginScanner scanner = new PluginScanner(pool);
			String[] types = scanner.findAllPluginClassNames();

			// Load types
			logger.info("Loading plugins...");
			for (String type : types) {
				// Load type
				logger.info("Loading type: " + type + "...");
				@SuppressWarnings("unchecked")
				Class<? extends IPolyscriptPlugin> cls = (Class<? extends IPolyscriptPlugin>) PolyJsonEmitter.class
						.getClassLoader().loadClass(type);

				// Get constructor
				Constructor<? extends IPolyscriptPlugin> ctor;
				try {
					ctor = cls.getConstructor();
				} catch (Exception e) {
					logger.error("Could not load plugin type " + type + ": no parameterless constructor!", e);
					return;
				}

				// Instantiate
				try {
					ctor.setAccessible(true);
					IPolyscriptPlugin plugin = ctor.newInstance();
					if (!plugins.containsKey(plugin.name()))
						plugins.put(plugin.name(), plugin);
				} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
						| InvocationTargetException e) {
					logger.error("Could not load plugin type " + type + ": constructor call failed!", e);
					return;
				}
			}

			// Log loaded plugins
			logger.info("Plugin loading finished!");
			for (String plugin : plugins.keySet()) {
				logger.info("Loaded plugin: " + plugin);
			}

			// Close pool
			logger.info("Clearing resources...");
			pool.close();

			// Log start
			logger.info("Starting script engine...");

			// Import
			engine.importScripts();

			// Setup
			engine.setupScripts();

			// Run evaluation engine
			engine.callEvaluate();

			// Emitter setup
			logger.info("Processing paths...");
			if (!sourceFile.isAbsolute())
				sourceFile = new File(engine.getMainScript().getWorkingDirectory(), sourceFile.getPath());
			if (!destFile.isAbsolute())
				destFile = new File(engine.getMainScript().getWorkingDirectory(), destFile.getPath());

			// Check existence
			if (!sourceFile.exists()) {
				System.err.println("Error: invalid argument: source file: file does not exist");
				System.exit(1);
				return;
			}

			// Multi-destination
			String[] dests = Arrays.copyOfRange(args, 2, args.length);
			ArrayList<File> destFiles = new ArrayList<File>();

			// Handle destinations
			for (String dest : dests) {
				// Map destination
				File destTarget = new File(dest);
				if (!destTarget.isAbsolute())
					destTarget = new File(engine.getMainScript().getWorkingDirectory(), destTarget.getPath());

				// Check type
				if (sourceFile.isDirectory() && destTarget.isFile()) {
					// Error
					System.err.println("Error: invalid argument: destination file: " + dest
							+ ": target is a file while the source is a directory");
					System.exit(1);
					return;
				}

				// Add
				destFiles.add(destTarget);
			}

			// Run emitter
			logger.info("Emitting processed files...");
			int i = 0;
			for (File dest : destFiles) {
				String destStr = dests[i++].replace("\\", "/");
				while (destStr.contains("//"))
					destStr = destStr.replace("//", "/");
				while (destStr.startsWith("/"))
					destStr = destStr.substring(1);
				while (destStr.endsWith("/"))
					destStr = destStr.substring(0, destStr.length() - 1);
				runEmitter(sourceFile, dest, destStr, engine);
			}

			// Post evaluation
			engine.callPostEvaluate();
			logger.info("Finished!");
			engine.close();
			engine = null;
		} finally {
			if (engine != null)
				engine.close();
		}
	}

	private static void runEmitter(File source, File dest, String prefix, PolyScriptEngine engine) throws IOException {
		// Check type
		if (source.isFile()) {
			// File
			// Write directly
			logger.info("Emitting: " + prefix + "...");

			// Destination
			File destFile = dest;
			if ((destFile.exists() && destFile.isDirectory()) || !destFile.getName().endsWith(".json")) {
				// Destination is a directory
				destFile = new File(destFile, source.getName());
			}

			// Create parent
			destFile.getAbsoluteFile().getParentFile().mkdirs();

			// Write
			if (source.getName().endsWith(".json")) {
				// Transform

				// Read source
				FileReader reader = new FileReader(source);
				JsonElement ele = JsonParser.parseReader(reader);
				reader.close();

				// Get script
				PolyScript script = engine.getMainScript();

				// Transform
				JsonElement eleTransformed = script.getVariablesProcessor().wrapElement(ele);

				// Write
				FileWriter writer = new FileWriter(destFile);
				new Gson().newBuilder().setPrettyPrinting().create().toJson(eleTransformed, writer);
				writer.close();
			} else {
				// Copy
				if (destFile.exists())
					destFile.delete();
				Files.copy(source.toPath(), destFile.toPath());
			}
		} else {
			// Directory
			logger.info("Emitting: " + prefix + "...");
			File destFile = dest;
			if (destFile.exists() && destFile.isFile()) {
				// Destination exists as file, this will break
				System.err.println("Error: invalid argument: destination file: " + prefix
						+ ": target is a file while the source is a directory, conflict cannot be resolved");
				System.exit(1);
				return;
			}

			// Create
			destFile.mkdirs();

			// Go through subdirectories
			for (File subdir : source.listFiles(t -> t.isDirectory())) {
				runEmitter(subdir, new File(destFile, subdir.getName()), prefix + "/" + subdir.getName(), engine);
			}

			// Go through files
			for (File f : source.listFiles(t -> t.isFile())) {
				runEmitter(f, new File(destFile, f.getName()), prefix + "/" + f.getName(), engine);
			}
		}
	}

}
