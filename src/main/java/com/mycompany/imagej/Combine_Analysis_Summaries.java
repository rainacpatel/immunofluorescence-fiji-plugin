package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.io.*;
import java.util.*;

public class Combine_Analysis_Summaries implements PlugIn {

    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Select Directory");
        gd.addDirectoryField("Select Folder: ", "");
        // Analyze_Intensity.java names its output after the method/line length used
        // (e.g. "analysis_summary_method1.csv", "analysis_summary_method2_6um.csv"),
        // so results from different settings never overwrite each other and can be
        // compiled separately here.
        gd.addStringField("Summary CSV filename to compile: ", "analysis_summary_method1.csv", 30);
        gd.addCheckbox("Skip folders that have not been analyzed", false);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        String parentDirPath = gd.getNextString();
        String csvFileName = gd.getNextString().trim();
        boolean skipMissing = gd.getNextBoolean();

        if (csvFileName.isEmpty()) {
            IJ.error("Please enter a summary CSV filename to compile.");
            return;
        }

        File parentDir = new File(parentDirPath);

        if (!parentDir.isDirectory()) {
            IJ.error("Invalid directory!");
            return;
        }

        File[] subdirs = parentDir.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length == 0) {
            IJ.error("No subdirectories found!");
            return;
        }

        List<String> missing = new ArrayList<>();
        Map<String, File> csvFiles = new TreeMap<>();
        for (File subdir : subdirs) {
            File maxProjFolder = null;
            File[] files = subdir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && f.getName().contains(" PROJECTIONS")) {
                        maxProjFolder = f;
                        break;
                    }
                }
            }
            // maxProjFolder stays null if no "* PROJECTIONS" folder was found.
            if (maxProjFolder == null) {
                missing.add(subdir.getName() + " (missing Projections)");
                continue;
            }

            File csvFile = new File(maxProjFolder, csvFileName);
            if (!csvFile.isFile()) {
                missing.add(subdir.getName() + " (missing " + csvFileName + ")");
            } else {
                csvFiles.put(subdir.getName(), csvFile);
            }
        }

        // Handle missing directories depending on user choice.
        if (!missing.isEmpty() && !skipMissing) {
            StringBuilder sb = new StringBuilder("The following directories are missing data:\n");
            for (String m : missing) {
                sb.append(m).append("\n");
            }
            IJ.showMessage(sb.toString());
            return;
        }

        if (csvFiles.isEmpty()) {
            IJ.error("No valid analysis_summary.csv files found!");
            return;
        }

        // Compile all CSVs into one, prefixing each row with its subdirectory name.
        // Output name mirrors the source filename so compiled results from different
        // methods/line lengths land in separate files too.
        String compiledName = "compiled_" + csvFileName;
        File outputFile = new File(parentDir, compiledName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            boolean headerWritten = false;

            for (Map.Entry<String, File> entry : csvFiles.entrySet()) {
                String subdirName = entry.getKey();
                File csv = entry.getValue();

                try (BufferedReader reader = new BufferedReader(new FileReader(csv))) {
                    String line;
                    boolean firstLine = true;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine) {
                            if (!headerWritten) {
                                writer.write("Subdirectory," + line);
                                writer.newLine();
                                headerWritten = true;
                            }
                            firstLine = false;
                        } else {
                            writer.write(subdirName + "," + line);
                            writer.newLine();
                        }
                    }
                }
            }

            IJ.showMessage("Complete", "Output: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            IJ.error("Error writing output CSV: " + e.getMessage());
        }
    }
}