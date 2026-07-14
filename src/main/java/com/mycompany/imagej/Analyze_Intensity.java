package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.ImageCalculator;
import ij.plugin.PlugIn;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.Prefs;

import java.awt.AWTEvent;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Analyze_Intensity implements PlugIn {

    /** Default line length, in micrometers, offered in the Method 2 options dialog. */
    private static final double DEFAULT_LINE_LENGTH_UM = 6.0;

    /** Exact area, in calibrated units^2, for the Method 1 background circle. */
    private static final double BACKGROUND_CIRCLE_AREA = 39.136;

    // Analysis method the user picks in the options dialog. Each constant carries
    // its own dialog label so there's one place to add/rename a method.
    private enum AnalysisMethod {
        METHOD1_THRESHOLD("Method 1: Junctional Threshold Mask"),
        METHOD2_LINE("Method 2: Manual Line");

        private final String label;

        AnalysisMethod(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        static String[] labels() {
            AnalysisMethod[] values = values();
            String[] out = new String[values.length];
            for (int i = 0; i < values.length; i++) out[i] = values[i].label;
            return out;
        }

        static AnalysisMethod fromLabel(String label) {
            for (AnalysisMethod m : values()) {
                if (m.label.equals(label)) return m;
            }
            throw new IllegalArgumentException("Unknown method: " + label);
        }
    }

    // Bundles the Method 1 mask-cleanup dialog answers, gathered once upfront and
    // reused for every folder, instead of threading six parameters through calls.
    private static class ThresholdOptions {
        final boolean manualJunction;
        final boolean manualNuclear;
        final int junctionCloseIterations;
        final int junctionOpenIterations;
        final int nuclearCloseIterations;
        final int nuclearOpenIterations;

        ThresholdOptions(boolean manualJunction, boolean manualNuclear,
                          int junctionCloseIterations, int junctionOpenIterations,
                          int nuclearCloseIterations, int nuclearOpenIterations) {
            this.manualJunction = manualJunction;
            this.manualNuclear = manualNuclear;
            this.junctionCloseIterations = junctionCloseIterations;
            this.junctionOpenIterations = junctionOpenIterations;
            this.nuclearCloseIterations = nuclearCloseIterations;
            this.nuclearOpenIterations = nuclearOpenIterations;
        }
    }

    @Override
    public void run(String arg) {

        // ---- Step 1: method + batch mode ----------------------------------------------
        GenericDialog optGd = new GenericDialog("Analysis Options");
        optGd.addChoice("Method:", AnalysisMethod.labels(), AnalysisMethod.METHOD1_THRESHOLD.toString());
        optGd.addNumericField("Method 2 - Line length (micrometers):", DEFAULT_LINE_LENGTH_UM, 2);
        optGd.addMessage("(Line length is ignored for Method 1.)");
        optGd.addMessage(" ");
        optGd.addCheckbox("Batch mode (process all subfolders inside selected folder)", false);
        optGd.showDialog();
        if (optGd.wasCanceled()) return;

        AnalysisMethod method = AnalysisMethod.fromLabel(optGd.getNextChoice());
        double lineLengthUm = optGd.getNextNumber();
        boolean batchMode = optGd.getNextBoolean();

        if (method == AnalysisMethod.METHOD2_LINE && lineLengthUm <= 0) {
            IJ.error("Fluorescence Junction Analyzer", "Line length must be greater than 0.");
            return;
        }

        // Gathered once upfront and reused for every folder, instead of
        // re-prompting per folder in batch mode.
        ThresholdOptions threshOpts = null;
        if (method == AnalysisMethod.METHOD1_THRESHOLD) {
            threshOpts = collectThresholdOptions();
            if (threshOpts == null) return; // cancelled
        }

        // Not strictly required since we measure via ImageStatistics directly, but
        // keeps Analyze > Measure in sync if the user checks anything manually.
        IJ.run("Set Measurements...", "area mean integrated redirect=None decimal=3");

        // ---- Step 2: choose folder(s) ---------------------------------------------------
        DirectoryChooser dc = new DirectoryChooser(
                batchMode ? "Select parent folder containing subfolders" : "Select image folder");
        String dir = dc.getDirectory();
        if (dir == null) return; // user cancelled

        List<File> foldersToProcess = new ArrayList<>();
        if (batchMode) {
            File[] subdirs = new File(dir).listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File sd : subdirs) foldersToProcess.add(sd);
            }
            if (foldersToProcess.isEmpty()) {
                IJ.showMessage("No subfolders found in the selected folder.");
                return;
            }
        } else {
            foldersToProcess.add(new File(dir));
        }

        // ---- Step 3: process each folder -------------------------------------------------
        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (File folder : foldersToProcess) {
            boolean ok = processOneFolder(folder, method, threshOpts, lineLengthUm, batchMode);
            if (ok) successes.add(folder.getName());
            else failures.add(folder.getName());
        }

        if (batchMode) {
            showBatchSummary(method, successes, failures);
        }
    }

    // Locates/opens the images, runs the selected method, and saves the summary
    // for one folder. Called once per folder in both single and batch mode.
    private boolean processOneFolder(File folder, AnalysisMethod method, ThresholdOptions threshOpts,
                                      double lineLengthUm, boolean batchMode) {
        ImagePlus dapiImp, ecadImp, poiImp;
        File imageDir;

        if (batchMode) {
            File projFolder = findProjectionsFolder(folder);
            if (projFolder == null) {
                IJ.log("Skipped " + folder.getName() + " — no '* PROJECTIONS' folder found.");
                return false;
            }
            File dapiFile = null;
            File ecadFile = null;
            File poiFile = null;

            int defaultDAPI = 0;
            int defaultECAD = 0;
            int defaultPOI = 0;

            for (File file : projFolder.listFiles()) {
                String name = file.getName();

                if (name.contains("DAPI_") && !name.contains("RGB") && !name.contains("overlay") && defaultDAPI == 0) {
                    dapiFile = file;
                    defaultDAPI = 1;
                }

                if (name.contains("Junction_") && !name.contains("RGB") && !name.contains("overlay") && defaultECAD == 0) {
                    ecadFile = file;
                    defaultECAD = 1;
                }

                if (name.contains("POI_") && !name.contains("RGB") && !name.contains("overlay") && defaultPOI == 0) {
                    poiFile = file;
                    defaultPOI = 1;
                }
            }
            
            if (dapiFile == null || ecadFile == null || poiFile == null) {
                IJ.log("Skipped " + folder.getName() + " — missing DAPI_/Junction_/POI_ image in: " + projFolder.getName());
                return false;
            }
            dapiImp = IJ.openImage(dapiFile.getAbsolutePath());
            ecadImp = IJ.openImage(ecadFile.getAbsolutePath());
            poiImp = IJ.openImage(poiFile.getAbsolutePath());
            imageDir = projFolder;
        } else {
            String[] selection = promptForImages(folder);
            if (selection == null) return false; // cancelled or no images found
            dapiImp = IJ.openImage(new File(folder, selection[0]).getAbsolutePath());
            ecadImp = IJ.openImage(new File(folder, selection[1]).getAbsolutePath());
            poiImp = IJ.openImage(new File(folder, selection[2]).getAbsolutePath());
            imageDir = folder;
        }

        if (dapiImp == null || ecadImp == null || poiImp == null) {
            IJ.log("Skipped " + folder.getName() + " — one or more images could not be opened.");
            return false;
        }
        dapiImp.hide();
        ecadImp.hide();
        poiImp.hide();

        ResultsTable folderSummary = new ResultsTable();
        if (method == AnalysisMethod.METHOD1_THRESHOLD) {
            runMethod1JunctionalThreshold(dapiImp, ecadImp, poiImp, imageDir.getAbsolutePath(), threshOpts, folderSummary);
        } else {
            runMethod2SixMicronLine(poiImp, folderSummary, lineLengthUm);
        }

        String baseFileName = summaryBaseFileName(method, lineLengthUm);
        boolean saved = saveSummary(folderSummary, imageDir.getAbsolutePath(), baseFileName, batchMode);

        // Close windows after each folder in batch mode to avoid clutter; leave
        // them open in single-folder mode.
        if (batchMode) {
            WindowManager.closeAllWindows();
        }

        return saved;
    }

    // Single-folder mode: lets the user assign DAPI/Junction/POI from dropdowns,
    // defaulting each to the first file matching its naming convention. Returns
    // {dapiFile, ecadFile, poiFile} names, or null if cancelled/no images found.
    private String[] promptForImages(File folder) {
        String[] fileList = folder.list((d, name) -> {
            String lname = name.toLowerCase();
            return lname.endsWith(".tif") || lname.endsWith(".tiff") ||
                   lname.endsWith(".jpg") || lname.endsWith(".png");
        });

        if (fileList == null || fileList.length == 0) {
            IJ.error("Fluorescence Junction Analyzer", "No image files found in the directory.");
            return null;
        }

        int defaultDAPI = 0, defaultJunction = 0, defaultPOI = 0;
        for (int i = 0; i < fileList.length; i++) {
            String name = fileList[i];
            if (name.contains("DAPI_") && (!name.contains("RGB")) && defaultDAPI == 0) defaultDAPI = i;
            if (name.contains("Junction_") && (!name.contains("RGB")) && defaultJunction == 0) defaultJunction = i;
            if (name.contains("POI_") && (!name.contains("RGB")) && defaultPOI == 0) defaultPOI = i;
        }

        GenericDialog gd = new GenericDialog("Select Images — " + folder.getName());
        gd.addChoice("DAPI image:", fileList, fileList[defaultDAPI]);
        gd.addChoice("Junction (E-cadherin) image:", fileList, fileList[defaultJunction]);
        gd.addChoice("Protein of Interest image:", fileList, fileList[defaultPOI]);
        gd.showDialog();
        if (gd.wasCanceled()) return null;

        return new String[]{gd.getNextChoice(), gd.getNextChoice(), gd.getNextChoice()};
    }

    // Finds the first child directory whose name contains " PROJECTIONS" — the
    // naming convention from Z_Projection_IF, also used by Combine_Analysis_Summaries.
    private File findProjectionsFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && f.getName().contains(" PROJECTIONS")) return f;
        }
        return null;
    }


    private ThresholdOptions collectThresholdOptions() {
        String[] thresholdModes = {"Automatic", "Manual"};
        GenericDialog gdThresh = new GenericDialog("Mask Threshold Options");
        gdThresh.addMessage("Choose how each mask's threshold should be determined.");
        gdThresh.addChoice("Junction mask thresholding:", thresholdModes, thresholdModes[0]);
        gdThresh.addChoice("Nuclear mask thresholding:", thresholdModes, thresholdModes[0]);
        gdThresh.addMessage("Optional mask cleanup:\nSet to 0 to skip.");
        gdThresh.addNumericField("Junction mask - Dilation/erosion size to close holes:", 0, 0);
        gdThresh.addNumericField("Junction mask - Erosion/dilation size to remove small, isolated dots:", 0, 0);
        gdThresh.addNumericField("Nuclear mask - Dilation/erosion size to close holes:", 0, 0);
        gdThresh.addNumericField("Nuclear mask - Erosion/dilation size to remove small, isolated noise:", 0, 0);
        gdThresh.showDialog();
        if (gdThresh.wasCanceled()) return null;

        boolean manualJunction = gdThresh.getNextChoice().equals("Manual");
        boolean manualNuclear = gdThresh.getNextChoice().equals("Manual");
        int junctionCloseIterations = (int) gdThresh.getNextNumber();
        int junctionOpenIterations = (int) gdThresh.getNextNumber();
        int nuclearCloseIterations = (int) gdThresh.getNextNumber();
        int nuclearOpenIterations = (int) gdThresh.getNextNumber();

        return new ThresholdOptions(manualJunction, manualNuclear,
                junctionCloseIterations, junctionOpenIterations,
                nuclearCloseIterations, nuclearOpenIterations);
    }

    // Base output filename (no extension), specific to the method — and, for
    // Method 2, the line length — so different settings never overwrite each
    // other and Combine_Analysis_Summaries.java can target one set at a time.
    private String summaryBaseFileName(AnalysisMethod method, double lineLengthUm) {
        if (method == AnalysisMethod.METHOD1_THRESHOLD) {
            return "analysis_summary_method1";
        } else {
            return "analysis_summary_method2_" + formatLength(lineLengthUm) + "um";
        }
    }

    // Formats a line length without a trailing ".0" for whole numbers (e.g. 6 not
    // 6.0), so filenames stay tidy for the common case.
    private String formatLength(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private void showBatchSummary(AnalysisMethod method, List<String> successes, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Method: ").append(method).append("\n\n");

        if (!successes.isEmpty()) {
            sb.append("Completed (").append(successes.size()).append("):\n");
            for (String s : successes) sb.append("  \u2713 ").append(s).append("\n");
        }
        if (!failures.isEmpty()) {
            sb.append("\nSkipped/failed (").append(failures.size()).append("):\n");
            for (String f : failures) sb.append("  \u2717 ").append(f).append("\n");
            sb.append("\nSee Fiji Log window for details.");
        }
        IJ.showMessage("Batch analysis complete", sb.toString());
    }

    // =====================================================================================
    // METHOD 1: Junctional Threshold (+ Nuclear / Cytoplasmic compartments)
    // =====================================================================================
    private void runMethod1JunctionalThreshold(ImagePlus dapiImp, ImagePlus ecadImp, ImagePlus poiImp, String dir,
                                                ThresholdOptions opts, ResultsTable table) {

        // ---- Junction mask from the Ecad image ------------------------------------------
        ImagePlus junctionMaskImp = opts.manualJunction
                ? createMaskFromImageManual(ecadImp, "JunctionMask")
                : createMaskFromImageAuto(ecadImp, "Default dark", "JunctionMask", false);
        if (junctionMaskImp == null) {
            IJ.error("Fluorescence Junction Analyzer", "Junction mask creation was cancelled or failed.");
            return;
        }
        applyErosionDilation(junctionMaskImp, opts.junctionCloseIterations, opts.junctionOpenIterations);

        Roi junctionRoi = ThresholdToSelection.run(junctionMaskImp);
        if (junctionRoi == null) {
            IJ.error("Fluorescence Junction Analyzer", "Could not create a selection from the junction mask.");
            junctionMaskImp.changes = false;
            junctionMaskImp.close();
            return;
        }

        // Overlay the junction mask selection on top of the POI image and measure.
        bringToFront(poiImp);
        poiImp.setRoi((Roi) junctionRoi.clone());
        double[] junction = measureCurrentRoi(poiImp);

        // ---- Nuclear mask from the DAPI image --------------------------------------------
        ImagePlus nuclearMaskImp = opts.manualNuclear
                ? createMaskFromImageManual(dapiImp, "NuclearMask")
                : createMaskFromImageAuto(dapiImp, "Huang dark", "NuclearMask", true);
        double[] nuclear = new double[]{0, 0, 0, 0};
        if (nuclearMaskImp != null) {
            applyErosionDilation(nuclearMaskImp, opts.nuclearCloseIterations, opts.nuclearOpenIterations);
            Roi nuclearRoi = ThresholdToSelection.run(nuclearMaskImp);
            if (nuclearRoi != null) {
                poiImp.setRoi((Roi) nuclearRoi.clone());
                nuclear = measureCurrentRoi(poiImp);
            } else {
                IJ.log("Fluorescence Junction Analyzer: could not create a selection from the nuclear mask.");
            }
        }

        // ---- Cytoplasmic mask = NOT (nuclear OR junction) --------------------------------
        double[] cytoplasmic = new double[]{0, 0, 0, 0};
        ImagePlus cytoMaskImp = null;
        if (nuclearMaskImp != null) {
            cytoMaskImp = createCytoplasmicMask(nuclearMaskImp, junctionMaskImp);
            Roi cytoRoi = ThresholdToSelection.run(cytoMaskImp);
            if (cytoRoi != null) {
                poiImp.setRoi((Roi) cytoRoi.clone());
                cytoplasmic = measureCurrentRoi(poiImp);
            } else {
                IJ.log("Fluorescence Junction Analyzer: could not create a selection from the cytoplasmic mask.");
            }
        }

        // ---- Background selection: place an exact-area circle, let the user drag it ------
        placeBackgroundOval(poiImp, BACKGROUND_CIRCLE_AREA);
        new WaitForUserDialog(
                "Background Selection",
                "A circular selection with an exact area of " + BACKGROUND_CIRCLE_AREA
                        + " (calibrated units^2) has been placed on " + poiImp.getTitle() + ".\n"
                        + "Drag it to a representative background region, then click OK."
        ).show();
        double[] background = measureCurrentRoi(poiImp);

        // ---- Save overlay TIFFs for each mask ---------------------------------------------
        saveOverlay(poiImp, junctionMaskImp, dir);
        if (nuclearMaskImp != null) saveOverlay(poiImp, nuclearMaskImp, dir);
        if (cytoMaskImp != null) saveOverlay(poiImp, cytoMaskImp, dir);

        // Clean up mask windows.
        junctionMaskImp.changes = false;
        junctionMaskImp.close();
        if (nuclearMaskImp != null) {
            nuclearMaskImp.changes = false;
            nuclearMaskImp.close();
        }
        if (cytoMaskImp != null) {
            cytoMaskImp.changes = false;
            cytoMaskImp.close();
        }

        // ---- Ratios (based on Mean intensity) ----------------------------------------------
        double jcRatio = (cytoplasmic[3] > 0) ? junction[3] / cytoplasmic[3] : Double.NaN;
        double jnRatio = (nuclear[3] > 0) ? junction[3] / nuclear[3] : Double.NaN;

        table.incrementCounter();
        table.addValue("POI Image", poiImp.getTitle());
        table.addValue("Junction Area", junction[0]);
        table.addValue("Junction IntDen", junction[1]);
        table.addValue("Junction RawIntDen", junction[2]);
        table.addValue("Junction Mean", junction[3]);
        table.addValue("Nuclear Area", nuclear[0]);
        table.addValue("Nuclear IntDen", nuclear[1]);
        table.addValue("Nuclear RawIntDen", nuclear[2]);
        table.addValue("Nuclear Mean", nuclear[3]);
        table.addValue("Cytoplasmic Area", cytoplasmic[0]);
        table.addValue("Cytoplasmic IntDen", cytoplasmic[1]);
        table.addValue("Cytoplasmic RawIntDen", cytoplasmic[2]);
        table.addValue("Cytoplasmic Mean", cytoplasmic[3]);
        table.addValue("Background Area", background[0]);
        table.addValue("Background IntDen", background[1]);
        table.addValue("Background RawIntDen", background[2]);
        table.addValue("Background Mean", background[3]);
        table.addValue("Junctional/Cytoplasmic (Mean)", jcRatio);
        table.addValue("Junctional/Nuclear (Mean)", jnRatio);
    }

    // Applies binary erosion followed by dilation ("close", to remove holes) and/or
    // dilation followed by erosion ("open", to remove small isolated dots) to the
    // given mask. Either count can be 0 to skip that step.
    private void applyErosionDilation(ImagePlus mask, int closeIterations, int openIterations) {
        Prefs.blackBackground = true;
        if (closeIterations > 0) {
            IJ.run(mask, "Dilate", "iterations=" + closeIterations + " count=1");
            IJ.run(mask, "Erode", "iterations=" + closeIterations + " count=1");
        }
        if (openIterations > 0) {
            IJ.run(mask, "Erode", "iterations=" + openIterations + " count=1");
            IJ.run(mask, "Dilate", "iterations=" + openIterations + " count=1");
        }
    }

    // Builds a mask from the given image using an automatic ImageJ threshold method
    // (e.g. "Default dark", "Huang dark"). Optionally applies a median filter
    // afterward (used for the nuclear/DAPI mask).
    private ImagePlus createMaskFromImageAuto(ImagePlus imp, String thresholdMethod, String maskTitle, boolean applyMedian) {
        ImagePlus dup = imp.duplicate();
        dup.setTitle(maskTitle);
        IJ.setAutoThreshold(dup, thresholdMethod);
        IJ.run(dup, "Convert to Mask", "");
        Prefs.blackBackground = true;
        if (applyMedian) {
            IJ.run(dup, "Median", "radius=3");
        }
        dup.hide();
        return dup;
    }

    // Builds a mask from the given image by letting the user drag a slider and see a
    // live threshold preview, then confirm. Applies Despeckle / Dilate / Fill Holes
    // cleanup afterward. Returns null if the user cancels.
    private ImagePlus createMaskFromImageManual(ImagePlus imp, String maskTitle) {
        ImagePlus copy = imp.duplicate();
        copy.setTitle(maskTitle + " (preview)");
        copy.show();

        ImageProcessor ip = copy.getProcessor().duplicate();
        final ImageProcessor ip2 = copy.getProcessor().duplicate();
        final ImageProcessor ipOrig = copy.getProcessor().duplicate();

        GenericDialog gd = new GenericDialog("Set Threshold - " + maskTitle);
        gd.addMessage("Adjust threshold for " + maskTitle + " generation.");
        gd.addSlider("Min Threshold", 0, 255, 100);

        gd.addDialogListener(new DialogListener() {
            @Override
            public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
                int threshold = (int) gd.getNextNumber();
                ipOrig.setPixels(ip2.getPixelsCopy());
                ipOrig.threshold(threshold);
                copy.setProcessor(ipOrig);
                copy.updateAndDraw();
                return true;
            }
        });

        gd.showDialog();
        if (gd.wasCanceled()) {
            copy.changes = false;
            copy.close();
            return null;
        }

        int threshold = (int) gd.getNextNumber();
        ip.threshold(threshold);
        copy.setProcessor(ip);
        copy.updateAndDraw();
        Prefs.blackBackground = true;
        IJ.run(copy, "Convert to Mask", "");
        IJ.run(copy, "Despeckle", "");
        IJ.run(copy, "Dilate", "");
        IJ.run(copy, "Fill Holes", "");
        copy.setTitle(maskTitle);
        copy.hide();
        return copy;
    }

    // Cytoplasmic mask = NOT (nuclear mask OR junction mask), i.e. everything that is
    // neither nucleus nor junction.
    private ImagePlus createCytoplasmicMask(ImagePlus nuclearMask, ImagePlus junctionMask) {
        ImageCalculator ic = new ImageCalculator();
        ImagePlus excludedMask = ic.run("Add create", nuclearMask, junctionMask);

        ImageProcessor excludedIP = excludedMask.getProcessor();
        excludedIP.invert();
        excludedIP.resetMinAndMax();
        excludedIP.setBinaryThreshold();
        return new ImagePlus("CytoplasmicMask", excludedIP);
    }

    // Draws the given mask as a semi-transparent yellow overlay on the source image,
    // flattens it, and saves it as a TIFF in the given directory.
    private boolean saveOverlay(ImagePlus source, ImagePlus mask, String dir) {
        Roi maskRoi = ThresholdToSelection.run(mask);
        if (maskRoi == null) return false;
        maskRoi.setFillColor(new Color(255, 255, 0, 128));
        maskRoi.setStrokeColor(Color.YELLOW);
        maskRoi.setStrokeWidth(3.0);
        Overlay maskOverlay = new Overlay(maskRoi);

        Overlay previousOverlay = source.getOverlay();
        source.setOverlay(maskOverlay);
        ImagePlus flattened = source.flatten();
        FileSaver fs = new FileSaver(flattened);
        boolean saved = fs.saveAsTiff(dir + "/overlay_" + source.getShortTitle() + "_" + mask.getShortTitle() + ".tif");
        source.setOverlay(previousOverlay);
        flattened.changes = false;
        flattened.close();
        return saved;
    }

    // =====================================================================================
    // METHOD 2: 6 um Line
    // =====================================================================================
    private void runMethod2SixMicronLine(ImagePlus imp, ResultsTable table, double lineLengthUm) {
        RoiManager rm = getRoiManager();
        rm.reset();
        bringToFront(imp);

        ArrayList<String> roiTypeLabels = new ArrayList<>();
        for (int rep = 1; rep <= 3; rep++) {
            if (!captureCalibratedLine(imp, rm, "junctional line (repeat " + rep + " of 3)", lineLengthUm)) return;
            roiTypeLabels.add("POI");

            if (!captureCalibratedLine(imp, rm, "background line (repeat " + rep + " of 3)", lineLengthUm)) return;
            roiTypeLabels.add("Background");
        }

        new WaitForUserDialog(
                "Confirm ROIs",
                "Have all datapoints been added to the ROI Manager? Click OK to proceed to measurement."
        ).show();

        int count = rm.getCount();
        for (int i = 0; i < count; i++) {
            rm.select(imp, i);
            double[] meas = measureCurrentRoi(imp);

            String roiType = roiTypeLabels.get(i);
            String pairLabel = "Pair " + ((i / 2) + 1);

            table.incrementCounter();
            table.addValue("Image", imp.getTitle());
            table.addValue("Pair", pairLabel);
            table.addValue("ROI Type", roiType);
            table.addValue("Area", meas[0]);
            table.addValue("IntDen", meas[1]);
            table.addValue("RawIntDen", meas[2]);
            table.addValue("Mean", meas[3]);
        }
    }

    // Prompts the user to draw a line and press 't' to add it to the ROI Manager,
    // rescales that line to an exact calibrated length (keeping its midpoint and
    // angle fixed), then lets the user reposition it without changing its length.
    // Returns false if no usable line ROI was captured, so the caller can stop
    // instead of recording a label for an ROI that was never added.
    private boolean captureCalibratedLine(ImagePlus imp, RoiManager rm, String description, double lengthUm) {
        int countBefore = rm.getCount();
        new WaitForUserDialog(
                "Draw Line",
                "Draw the " + description + " and press 't' to add it to the ROI Manager.\nClick OK when done."
        ).show();

        if (rm.getCount() <= countBefore) {
            IJ.error("Fluorescence Junction Analyzer",
                    "No new ROI was added to the ROI Manager for the " + description + ". Please rerun.");
            return false;
        }

        int index = rm.getCount() - 1;
        rm.select(imp, index);
        Roi roi = imp.getRoi();
        if (!(roi instanceof Line)) {
            IJ.error("Fluorescence Junction Analyzer",
                    "The most recently added ROI is not a line (" + description + ").");
            return false;
        }

        adjustLineLength(imp, rm, index, lengthUm);

        // Let the user fine-tune position without changing length.
        new WaitForUserDialog(
                "Confirm Position",
                "Confirm the " + description + " is in the correct position, then click OK.\n"
                        + "(You may reposition it without changing its length.)"
        ).show();

        rm.select(imp, index);
        rm.runCommand("Update");
        return true;
    }

    // Rescales a Line ROI already in the ROI Manager to an exact calibrated length,
    // using the image's pixel calibration, keeping the line's midpoint and angle
    // unchanged.
    private void adjustLineLength(ImagePlus imp, RoiManager rm, int index, double lengthUm) {
        rm.select(imp, index);
        Roi roi = imp.getRoi();
        if (!(roi instanceof Line)) return;

        Line line = (Line) roi;
        Calibration cal = imp.getCalibration();
        double pixelSize = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : 1.0;
        double targetLengthPixels = lengthUm / pixelSize;

        double x1 = line.x1d, y1 = line.y1d, x2 = line.x2d, y2 = line.y2d;
        double midX = (x1 + x2) / 2.0;
        double midY = (y1 + y2) / 2.0;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double currentLength = Math.sqrt(dx * dx + dy * dy);

        if (currentLength == 0) {
            IJ.error("Fluorescence Junction Analyzer", "The drawn line has zero length.");
            return;
        }

        double scale = targetLengthPixels / currentLength;
        double newX1 = midX - (dx / 2.0) * scale;
        double newY1 = midY - (dy / 2.0) * scale;
        double newX2 = midX + (dx / 2.0) * scale;
        double newY2 = midY + (dy / 2.0) * scale;

        // IMPORTANT: do NOT call rm.select(index) here — that would re-fetch the
        // original, un-resized line from the manager and redraw it on the image,
        // undoing the resize above before Update ever runs. "Update" saves whatever
        // ROI is currently on imp into the still-selected manager slot from the
        // rm.select(imp, index) call at the top of this method, so just set the
        // resized line on imp and update — no reselection needed.
        imp.setRoi(new Line(newX1, newY1, newX2, newY2));
        rm.runCommand("Update");
    }

    // =====================================================================================
    // Shared helpers
    // =====================================================================================

    private RoiManager getRoiManager() {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            rm = new RoiManager();
        }
        rm.setVisible(true);
        return rm;
    }

    private void bringToFront(ImagePlus imp) {
        imp.show();
        IJ.selectWindow(imp.getTitle());
        if (imp.getWindow() != null) {
            imp.getWindow().toFront();
        }
    }

    // Places an oval ROI on the image with an EXACT calibrated area, centered on the
    // image, ready for the user to drag to a background region.
    private void placeBackgroundOval(ImagePlus imp, double calibratedArea) {
        bringToFront(imp);
        Calibration cal = imp.getCalibration();
        double pixelSize = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : 1.0;

        double radiusCalibrated = Math.sqrt(calibratedArea / Math.PI);
        double radiusPixels = radiusCalibrated / pixelSize;
        double diameterPixels = radiusPixels * 2.0;

        double x = (imp.getWidth() / 2.0) - radiusPixels;
        double y = (imp.getHeight() / 2.0) - radiusPixels;

        imp.setRoi(new OvalRoi(x, y, diameterPixels, diameterPixels));
    }

    // Measures the current ROI on the given image and returns
    // {Area, IntDen, RawIntDen, Mean}. IntDen = calibrated Area x Mean;
    // RawIntDen = pixel count x raw (uncalibrated) mean.
    private double[] measureCurrentRoi(ImagePlus imp) {
        Roi roi = imp.getRoi();
        if (roi == null) {
            IJ.error("Fluorescence Junction Analyzer", "No selection found on " + imp.getTitle() + " to measure.");
            return new double[]{0, 0, 0, 0};
        }

        ImageProcessor ip = imp.getProcessor();
        ip.setRoi(roi);

        int measurements = Measurements.AREA | Measurements.MEAN | Measurements.INTEGRATED_DENSITY;
        ImageStatistics calibratedStats = ImageStatistics.getStatistics(ip, measurements, imp.getCalibration());
        ImageStatistics rawStats = ImageStatistics.getStatistics(ip, measurements, null);

        double area = calibratedStats.area;
        double mean = calibratedStats.mean;
        double intDen = calibratedStats.area * calibratedStats.mean;
        double rawIntDen = rawStats.pixelCount * rawStats.mean;

        return new double[]{area, intDen, rawIntDen, mean};
    }

    // Saves one folder's summary table under baseFileName (see summaryBaseFileName()).
    // In batch mode, saves silently as "<baseFileName>.csv" with no per-folder
    // prompts. In single-folder mode, shows the results table and lets the user
    // pick the format/location, defaulting to the same base name.
    private boolean saveSummary(ResultsTable table, String dir, String baseFileName, boolean batchMode) {
        if (table.size() == 0) {
            IJ.log("Fluorescence Junction Analyzer: no measurements were recorded for " + dir);
            return false;
        }

        if (batchMode) {
            String path = dir + File.separator + baseFileName + ".csv";
            try {
                table.save(path);
                IJ.log("Saved: " + path);
                return true;
            } catch (Exception e) {
                IJ.log("Failed to save summary for " + dir + ": " + e.getMessage());
                return false;
            }
        }

        GenericDialog gd = new GenericDialog("Save Summary Table");
        gd.addMessage("Analysis complete. Save the summary table.");
        String[] formats = {"CSV (.csv)", "Excel (.xls)"};
        gd.addChoice("File format:", formats, formats[0]);
        gd.showDialog();

        table.show("Fluorescence Junction Analyzer - Summary");
        if (gd.wasCanceled()) return true;

        String choice = gd.getNextChoice();
        String extension = choice.startsWith("CSV") ? ".csv" : ".xls";

        SaveDialog sd = new SaveDialog("Save Summary Table", dir, baseFileName, extension);
        String outDir = sd.getDirectory();
        String name = sd.getFileName();
        if (outDir == null || name == null) return true;

        try {
            table.save(outDir + name);
            IJ.log("Summary table saved to " + outDir + name);
            return true;
        } catch (Exception e) {
            IJ.error("Fluorescence Junction Analyzer", "Error saving file: " + e.getMessage());
            return false;
        }
    }
}