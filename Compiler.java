package uk.ac.sussex.submissions.utilities;

import lombok.SneakyThrows;
import org.springframework.data.util.Pair;
import org.springframework.util.FileSystemUtils;
import uk.ac.sussex.submissions.controllers.objects.OutputlessFileManager;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Class for compiling students' submissions
 *
 */
public class Compiler {


    /**
     * Compiles interfaces
     * @param source folder containing our interfaces
     * @param destination where to place the compiled interfaces
     * @return a pair consisting of a boolean and a list of strings. The boolean is whether the compilation succeeded, and the list of strings is the list of errors
     */
    public Pair<Boolean, List<String>> compileInterfaces(Path source, Path destination) {
        return compile(Collections.singletonList(source), destination, false, true);
    }

    /**
     * Compiles tests
     * @param sources folders containing our interfaces and tests
     * @param destination where to place the compiled files
     * @return a pair consisting of a boolean and a list of strings. The boolean is whether the compilation succeeded, and the list of strings is the list of errors
     */
    public Pair<Boolean, List<String>> compileTests(List<Path> sources, Path destination) {
        return compile(sources, destination, false, false);
    }

    /**
     * Compiles submission
     * @param sources folder containing interfaces, tests, and the submission
     * @param destination where to place the compiled result
     * @return a pair consisting of a boolean and a list of strings. The boolean is whether the compilation succeeded, and the list of strings is the list of errors
     */
    public Pair<Boolean, List<String>> compileSubmission(List<Path> sources, Path destination) {
        return compile(sources, destination, true, false);
    }

    /**
     * Method to call the compiler
     * @param sources folders we are compiling
     * @param destination where to put the result
     * @param enforceImplementation whether we enforce that every interface has to be implemented
     * @param createFactory whether we should create a factory based on the interfaces provided
     * @return a pair consisting of a boolean and a list of strings. The boolean is whether the compilation succeeded, and the list of strings is the list of errors
     */
    private Pair<Boolean, List<String>> compile(List<Path> sources, Path destination, boolean enforceImplementation, boolean createFactory) {

        // Get the compiler
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // The error collector
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();

        // And our standard file managers
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        /*

        "Gee Nich, how come your mother lets you have TWO file managers?"

        You might be asking yourself: "But Nich, why do you need two filemanagers? Wouldn't one suffice?", the answer to
        which would usually be yes, but I would prefer to avoid having a billion class files output that I need to delete.

        So, if the method caller supplies an empty string as the destination, we use the outputless file manager, which
        just sends all the files to an empty void that's never written to.

        */

        // Get all our file objects from the file manager
        Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromFiles(searchPath(sources, ".java"));

        // Whether or not the compilation succeeded.
        boolean success;

        // Whether we're outputting to a temporary folder
        boolean temp = destination == null || destination.toString().equals("");

        Path outputDestination = destination;


        // if the destination is null, send the output to the outputless file manager
        if (temp && !(enforceImplementation || createFactory)) {

            // Compile it
            success = compiler.getTask(null, new OutputlessFileManager(fileManager),
                    diagnostics, null, null, files).call();

        //  If our destination is null and we *are* enforcing implementation or creating a factory
        } else if (temp){

            /*
                In this case, we need to write the output to a dummy folder, which we can delete when we're done.
                So, we will go create a /temp/ folder. In this /temp/ folder, we will just number the entries.

                Starting at 0, keep increasing the number untill you find one that doesn't exist, then create that.
                Then delete it when you're done with it
             */
            int index = 0;

            // If the directory exists, increment
            while (new File("temp/"+String.valueOf(index)).exists()) {
                index++;
            }

            // Now that we've found an index that doesn't exist, create it
            File f =  new File("temp/"+String.valueOf(index));
            f.mkdirs();

            // Compile it
            success = compiler.getTask(null, fileManager, diagnostics,
                    Arrays.asList("-d", "temp/"+String.valueOf(index)), null, files).call();

            // Then set the destination value, so we know to deal with it when checking the implementation value
            outputDestination = Paths.get("temp/" + String.valueOf(index));


        // Otherwise, send it to the given destination with the argument for destination
        } else {

            File outputFile = outputDestination.toFile();
            if(!outputFile.exists()) {
                outputFile.mkdirs();
            }

            // Compile it
            success = compiler.getTask(null, fileManager, diagnostics,
                    Arrays.asList("-d", destination.toString()), null, files).call();
        }

        // Create our list of error messages
        List<String> errors = new ArrayList<>();

        // Then go through all our diagnostics
        for (Diagnostic errmsg : diagnostics.getDiagnostics()) {
            if (errmsg.getKind().equals(Diagnostic.Kind.WARNING)) {
                continue;
            }
            errors.add(errmsg.toString());
        }

        // If we're creating a factory, we want to send the interface to the first source, and the implementation to the
        // destination.
        if (success && createFactory) {
            createFactory(sources.get(0), Paths.get(sources.get(0).toString().substring(0, sources.get(0).toString().length()-10) + "factory"), outputDestination);
        }

        // If we're enforcing interface implementation, we probably oughta go in and do that
        // Only do it if compilation succeeded though, since we
        if (success && enforceImplementation) {

            // Check whether the interfaces are actually implemented
            Pair<Boolean, List<String>> impPair = checkImplementation(outputDestination);

            // Update our success value
            success = impPair.getFirst();

            // Then add all the unimplemented interfaces to the error message list
            errors.addAll(impPair.getSecond());

        }

        // If we created a temporary folder, we delete it
       if(temp && (createFactory || enforceImplementation)) {
            File f = new File(outputDestination.toUri());
            FileSystemUtils.deleteRecursively(f);
        }

        return Pair.of(success, errors);
    }

    /**
     * Goes through the supplied list of files, finds all class files, and makes sure every interface has an implementing
     * class.
     * @param source folder to search through
     * @return a pair consisting of a boolean signifying whether it compiles, and a list of unimplemented interfaces
     */
    @SneakyThrows
    private Pair<Boolean, List<String>> checkImplementation(Path source)  {
        // Our map of classes and booleans
        Map<String, Boolean> map = new HashMap<>();

        // The classloader we will use
        ClassLoader classLoader = URLClassLoader.newInstance(new URL[]{source.toUri().toURL()});

        // Create our list of files
        List<File> files = searchPath(Collections.singletonList(source), ".class");

        // Loop through all the files
        for (File file : files) {

            // Get the name from the file
            String name = file.getPath().substring(7, file.getPath().length()-6).replaceAll("\\\\", ".").replaceAll("/", ".");

            // The class we're dealing with this time
            Class<?> c;

            // find the class with the given name

            try {
                c = Class.forName(name, false, classLoader);

            // Something's really wrong if we can't find the given class.
            // Like really really wrong. Because we only just went and made sure it exists
            // So I'm not too worried this would happen, but you know what Java is like
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                return Pair.of(false, Collections.singletonList("Class not found, please inform a system administator"));
            }

            // Now that we have a class, check if it's an interface
            if (c.isInterface()) {

                // If the map doesn't already contain the class,
                if(!map.containsKey(c.getName())) {
                    map.put(c.getName(), false);
                }

            }

            // Then we check if it implements anything
            for (Class<?> intface : c.getInterfaces()) {

                // If it does, put that interface in the map, and mark it as true
                map.put(intface.getName(), true);
            }
        }

        // Create our list of unimplemented interfaces
        List<String> interfaces = new ArrayList<>();

        // Loop through all our interfaces
        for (Map.Entry<String, Boolean> entry : map.entrySet()) {

            System.out.println(entry.getKey());

            // If the interface wasn't implemented
            if (!entry.getValue()) {
                // Add it to our list
                interfaces.add(entry.getKey());
            }
        }


        return Pair.of(interfaces.size() == 0, interfaces);
    }


    /**
     * Searches through the list of directories recursively, and finds any file that matches the file extension.
     * @param sources the list of sources to search through
     * @param extension file extension to match. If empty, will match all files
     * @return list of all the matching files in the directory
     */
    public List<File> searchPath(List<Path> sources, String extension) {

        // Create our list of files
        List<File> filesToSearch = new ArrayList<>();

        // Go through our list of paths
        for (Path p : sources) {
            // And add all the files in the paths
            filesToSearch.addAll(Arrays.asList(p.toFile().listFiles()));
        }

        // Next, create our list of files we've found
        List<File> files = new ArrayList<>();

        // Now search through the files we want to search
        while(!filesToSearch.isEmpty()) {

            // Get the first file
            File f = filesToSearch.get(0);

            // if it's a directory, add all the paths in the directory to the list
            if(f.isDirectory()) {
                filesToSearch.addAll(Arrays.asList(f.listFiles()));

            // Otherwise, check if the file has the correct extension (If we're looking for one at all)
            } else if(f.getPath().substring(f.getPath().length()-extension.length()).equals(extension)) {
                files.add(f);
            }

            // Then remove the file we just searched
            filesToSearch.remove(0);
        }

        return files;
    }

    /**
     * Creates a factory interface in the interfaceDirectory, and an implementation of that in the factoryDirectory
     * @param interfaceDirectory directory of the interfaces
     * @param factoryDirectory directory to place the factory in
     * @param classFileDirectory directory where all the compiled classFiles are stored
     */
    @SneakyThrows
    private void createFactory(Path interfaceDirectory, Path factoryDirectory, Path classFileDirectory) {

        // List of interfaces to implement
        List<String> interfaces = new ArrayList<>();

        File factoryFolder = new File(factoryDirectory.toUri());
        factoryFolder.mkdirs();

        // Get our class files
        List<File> classFiles = searchPath(Collections.singletonList(classFileDirectory), ".class");

        // Loop through our files
        for (File file : classFiles) {
            // Get the name from the file
            String name = file.getName().substring(0, file.getName().length()-6);

            // Add the interface to the
            interfaces.add(name);
        }


        // Start writing to our files
        try {
            // First, create them
            FileWriter factoryInterface = new FileWriter(interfaceDirectory.toString()+"/FactoryInterface.java");
            FileWriter factoryImplementation = new FileWriter(factoryDirectory.toString()+"/Factory.java");

            // Write the imports
            factoryInterface.write("package uk.ac.sussex.submissions.interfaces;\n\n");
            factoryImplementation.write("package uk.ac.sussex.submissions.factory;\nimport uk.ac.sussex.submissions.interfaces.*;\n\n");
            // Write the class signatures
            factoryInterface.write("public interface FactoryInterface {\n\n");
            factoryImplementation.write("public class Factory implements FactoryInterface {\n\n");

            // Loop through all the interfaces, and add the methods
            for (String intface : interfaces) {
                factoryInterface.write("\tpublic abstract " + intface + " create" + intface + "(String arg);\n\n");
                factoryImplementation.write("\tpublic " + intface + " create" + intface + "(String arg){return null;}\n\n");
            }
            // Then close the class
            factoryInterface.write("}");
            factoryImplementation.write("}");

            // Then close the files
            factoryInterface.close();
            factoryImplementation.close();

        } catch (IOException e) {
            e.printStackTrace();
            return;
        }






    }
}
