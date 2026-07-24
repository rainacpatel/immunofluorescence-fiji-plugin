package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.io.*;
import java.util.*;

public class Combine_Analysis_Summaries implements PlugIn {

    // Simple holder for one data row plus which subdirectory it came from
    private static class RowRecord {
        String subdir;
        String[] fields;
        RowRecord(String subdir, String[] fields) {
            this.subdir = subdir;
            this.fields = fields;
        }
    }

    public void run(String arg) {
        GenericDialog gd = new GenericDialog("Select Directory");
        gd.addDirectoryField("Select Folder: ", "");

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

        if (!missing.isEmpty() && !skipMissing) {
            StringBuilder sb = new StringBuilder("The following directories are missing data:\n");
            for (String m : missing) {
                sb.append(m).append("\n");
            }
            IJ.showMessage(sb.toString());
            return;
        }

        if (csvFiles.isEmpty()) {
            IJ.error("No valid analysis summary files found. Please check the summary CSV filename before compiling.");
            return;
        }

        // ---- Pass 1: read everything into memory, so we know the header and can compute averages ----
        String[] header = null;
        List<RowRecord> rows = new ArrayList<>();

        try {
            for (Map.Entry<String, File> entry : csvFiles.entrySet()) {
                String subdirName = entry.getKey();
                File csv = entry.getValue();

                try (BufferedReader reader = new BufferedReader(new FileReader(csv))) {
                    String line;
                    boolean firstLine = true;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        if (firstLine) {
                            if (header == null) {
                                header = splitCsvLine(line);
                            }
                            firstLine = false;
                        } else {
                            rows.add(new RowRecord(subdirName, splitCsvLine(line)));
                        }
                    }
                }
            }
        } catch (IOException e) {
            IJ.error("Error reading input CSV files: " + e.getMessage());
            return;
        }

        if (header == null || rows.isEmpty()) {
            IJ.error("No data found in the selected summary CSV files.");
            return;
        }

        // ---- Determine which method this is, based on the header columns present ----
        int idxBackgroundMean = indexOf(header, "Background Mean");
        int idxRoiType = indexOf(header, "ROI Type");

        boolean isMethod1 = csvFileName.startsWith("analysis_summary_method1");
        boolean isMethod2 = csvFileName.startsWith("analysis_summary_method2");

        if (isMethod1 && (idxBackgroundMean < 0
                || indexOf(header, "Junction Area") < 0
                || indexOf(header, "Junction IntDen") < 0
                || indexOf(header, "Junction Mean") < 0
                || indexOf(header, "Cytoplasmic Area") < 0
                || indexOf(header, "Cytoplasmic IntDen") < 0
                || indexOf(header, "Cytoplasmic Mean") < 0
                || indexOf(header, "Nuclear Area") < 0
                || indexOf(header, "Nuclear IntDen") < 0
                || indexOf(header, "Nuclear Mean") < 0)) {
            IJ.error("Filename starts with 'analysis_summary_method1' but the expected columns "
                    + "(Junction/Cytoplasmic/Nuclear Area, IntDen & Mean, Background Mean) were not found in the header.");
            return;
        }

        if (isMethod2 && (idxRoiType < 0
                || indexOf(header, "Area") < 0
                || indexOf(header, "IntDen") < 0
                || indexOf(header, "Mean") < 0)) {
            IJ.error("Filename starts with 'analysis_summary_method2' but the expected columns "
                    + "(ROI Type, Area, IntDen, Mean) were not found in the header.");
            return;
        }

        String[] newColumnNames;
        double avgBackgroundMean1 = 0;   // used for method 1
        double avgBackgroundMean2 = 0;   // used for method 2

        int idxJunctionArea = -1, idxJunctionIntDen = -1, idxJunctionMean = -1;
        int idxCytoArea = -1, idxCytoIntDen = -1, idxCytoMean = -1;
        int idxNucArea = -1, idxNucIntDen = -1, idxNucMean = -1;
        int idxArea2 = -1, idxIntDen2 = -1, idxMean2 = -1;

        if (isMethod1) {
            idxJunctionArea = indexOf(header, "Junction Area");
            idxJunctionIntDen = indexOf(header, "Junction IntDen");
            idxJunctionMean = indexOf(header, "Junction Mean");
            idxCytoArea = indexOf(header, "Cytoplasmic Area");
            idxCytoIntDen = indexOf(header, "Cytoplasmic IntDen");
            idxCytoMean = indexOf(header, "Cytoplasmic Mean");
            idxNucArea = indexOf(header, "Nuclear Area");
            idxNucIntDen = indexOf(header, "Nuclear IntDen");
            idxNucMean = indexOf(header, "Nuclear Mean");

            avgBackgroundMean1 = average(rows, idxBackgroundMean, null, -1, null);

            newColumnNames = new String[] {
                    "Junction CTCF", "Cytoplasmic CTCF", "Nuclear CTCF",
                    "Junction Corrected Mean", "Cytoplasmic Corrected Mean", "Nuclear Corrected Mean",
                    "Junctional/Cytoplasmic (Corrected Mean)", "Junctional/Nuclear (Corrected Mean)"
            };
        } else if (isMethod2) {
            idxArea2 = indexOf(header, "Area");
            idxIntDen2 = indexOf(header, "IntDen");
            idxMean2 = indexOf(header, "Mean");

            avgBackgroundMean2 = average(rows, idxMean2, header, idxRoiType, "Background");

            newColumnNames = new String[] { "CTCF", "Corrected Mean" };
        } else {
            // Unrecognized format: fall back to the original plain-merge behavior, no extra columns.
            newColumnNames = new String[0];
        }

        // ---- Pass 2: write the compiled output, with computed columns prepended ----
        String compiledName = "compiled_" + csvFileName;
        File outputFile = new File(parentDir, compiledName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            // Header
            StringBuilder headerLine = new StringBuilder("Subdirectory");
            for (String col : newColumnNames) {
                headerLine.append(",").append(col);
            }
            for (String col : header) {
                headerLine.append(",").append(col);
            }
            writer.write(headerLine.toString());
            writer.newLine();

            for (RowRecord row : rows) {
                StringBuilder line = new StringBuilder(row.subdir);

                if (isMethod1) {
                    double junctionArea = parseDoubleSafe(row.fields, idxJunctionArea);
                    double junctionIntDen = parseDoubleSafe(row.fields, idxJunctionIntDen);
                    double cytoArea = parseDoubleSafe(row.fields, idxCytoArea);
                    double cytoIntDen = parseDoubleSafe(row.fields, idxCytoIntDen);
                    double nucArea = parseDoubleSafe(row.fields, idxNucArea);
                    double nucIntDen = parseDoubleSafe(row.fields, idxNucIntDen);
                    double junctionMean = parseDoubleSafe(row.fields, idxJunctionMean);
                    double cytoMean = parseDoubleSafe(row.fields, idxCytoMean);
                    double nucMean = parseDoubleSafe(row.fields, idxNucMean);

                    double junctionCTCF = junctionIntDen - (junctionArea * avgBackgroundMean1);
                    double cytoCTCF = cytoIntDen - (cytoArea * avgBackgroundMean1);
                    double nucCTCF = nucIntDen - (nucArea * avgBackgroundMean1);
                    double junctionCMean = junctionMean - avgBackgroundMean1;
                    double cytoCMean = cytoMean - avgBackgroundMean1;
                    double nucCMean = nucMean - avgBackgroundMean1;
                    double junctionOverCyto = safeDivide(junctionCMean, cytoCMean);
                    double junctionOverNuc = safeDivide(junctionCMean, nucCMean);

                    line.append(",").append(fmt(junctionCTCF));
                    line.append(",").append(fmt(cytoCTCF));
                    line.append(",").append(fmt(nucCTCF));
                    line.append(",").append(fmt(junctionCMean));
                    line.append(",").append(fmt(cytoCMean));
                    line.append(",").append(fmt(nucCMean));
                    line.append(",").append(fmt(junctionOverCyto));
                    line.append(",").append(fmt(junctionOverNuc));
                } else if (isMethod2) {
                    double area = parseDoubleSafe(row.fields, idxArea2);
                    double intDen = parseDoubleSafe(row.fields, idxIntDen2);
                    double mean = parseDoubleSafe(row.fields, idxMean2);
                    double ctcf = intDen - (area * avgBackgroundMean2);
                    double Cmean = mean - avgBackgroundMean2;


                    line.append(",").append(fmt(ctcf));
                    line.append(",").append(fmt(Cmean));
                }

                for (String field : row.fields) {
                    line.append(",").append(field);
                }
                writer.write(line.toString());
                writer.newLine();
            }

            IJ.showMessage("Complete", "Output: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            IJ.error("Error writing output CSV: " + e.getMessage());
        }
    }

    /** Splits a CSV line on commas. Assumes no embedded/quoted commas in the data (consistent with this plugin's input files). */
    private static String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    private static double parseDoubleSafe(String[] fields, int idx) {
        if (idx < 0 || idx >= fields.length) return 0;
        try {
            return Double.parseDouble(fields[idx].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double safeDivide(double numerator, double denominator) {
        if (denominator == 0) return Double.NaN;
        return numerator / denominator;
    }

    private static String fmt(double value) {
        if (Double.isNaN(value)) return "NaN";
        return String.format(Locale.US, "%.3f", value);
    }

    /**
     * Averages the given column across all rows.
     * If filterColIdx >= 0, only rows where header[filterColIdx]'s value equals filterValue (ignoring case) are included.
     */
    private static double average(List<RowRecord> rows, int colIdx, String[] header, int filterColIdx, String filterValue) {
        double sum = 0;
        int count = 0;
        for (RowRecord row : rows) {
            if (filterColIdx >= 0) {
                if (filterColIdx >= row.fields.length) continue;
                if (!row.fields[filterColIdx].trim().equalsIgnoreCase(filterValue)) continue;
            }
            if (colIdx < 0 || colIdx >= row.fields.length) continue;
            try {
                sum += Double.parseDouble(row.fields[colIdx].trim());
                count++;
            } catch (NumberFormatException ignored) {
                // skip malformed value
            }
        }
        return count == 0 ? 0 : sum / count;
    }
}