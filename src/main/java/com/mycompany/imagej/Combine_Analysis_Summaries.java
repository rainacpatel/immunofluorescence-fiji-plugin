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
        gd.addCheckbox("Skip folders that have not been analyzed", false);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        String parentDirPath = gd.getNextString();
        boolean skipMissing = gd.getNextBoolean();
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
            File maxProjFolder = new File(subdir, "Max Projections");
            if (!maxProjFolder.isDirectory()) {
                missing.add(subdir.getName() + " (missing 'Max Projections')");
                continue;
            }

            File csvFile = new File(maxProjFolder, "analysis_summary.csv");
            if (!csvFile.isFile()) {
                missing.add(subdir.getName() + " (missing analysis_summary.csv)");
            } else {
                csvFiles.put(subdir.getName(), csvFile);
            }
        }

        // Handle missing directories depending on user choice
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

        // Compile all CSVs into one
        File outputFile = new File(parentDir, "compiled_analysis_summary.csv");
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
