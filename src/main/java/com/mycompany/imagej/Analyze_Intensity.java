package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.ImagePlus;
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

public class Analyze_Intensity implements PlugIn {

    /** Target length, in micrometers, for Method 2 junctional/background lines. */
    private static final double LINE_LENGTH_UM = 6.0;

    /** Exact area, in calibrated units^2, for the Method 1 background circle. */
    private static final double BACKGROUND_CIRCLE_AREA = 39.136;

    private final ResultsTable summary = new ResultsTable();

    @Override
    public void run(String arg) {

        // ---- Step 1: choose directory and assign images ---------------------------------
        DirectoryChooser dc = new DirectoryChooser("Choose Image Directory");
        String dir = dc.getDirectory();
        if (dir == null) return; // user cancelled

        File folder = new File(dir);
        String[] fileList = folder.list((d, name) -> {
            String lname = name.toLowerCase();
            return lname.endsWith(".tif") || lname.endsWith(".tiff") ||
                   lname.endsWith(".jpg") || lname.endsWith(".png");
        });

        if (fileList == null || fileList.length == 0) {
            IJ.error("Fluorescence Junction Analyzer", "No image files found in the directory.");
            return;
        }

        // defaults, guessed from filename conventions
        int defaultDAPI = 0, defaultJunction = 0, defaultPOI = 0;
        for (int i = 0; i < fileList.length; i++) {
            String name = fileList[i];
            if (name.contains("DAPI_") && (!name.contains("RGB")) && defaultDAPI == 0) defaultDAPI = i;
            if (name.contains("Junction_") && (!name.contains("RGB")) && defaultJunction == 0) defaultJunction = i;
            if (name.contains("POI_") && (!name.contains("RGB")) && defaultPOI == 0) defaultPOI = i;
        }

        GenericDialog gdAssign = new GenericDialog("Select Images");
        gdAssign.addChoice("DAPI image:", fileList, fileList[defaultDAPI]);
        gdAssign.addChoice("Junction (E-cadherin) image:", fileList, fileList[defaultJunction]);
        gdAssign.addChoice("Protein of Interest image:", fileList, fileList[defaultPOI]);
        gdAssign.showDialog();
        if (gdAssign.wasCanceled()) return;

        String dapiFile = gdAssign.getNextChoice();
        String ecadFile = gdAssign.getNextChoice();
        String poiFile = gdAssign.getNextChoice();

        ImagePlus dapiImp = IJ.openImage(dir + dapiFile);
        ImagePlus ecadImp = IJ.openImage(dir + ecadFile);
        ImagePlus poiImp = IJ.openImage(dir + poiFile);
        if (dapiImp == null || ecadImp == null || poiImp == null) {
            IJ.error("Fluorescence Junction Analyzer", "One or more images could not be opened.");
            return;
        }
        dapiImp.hide();
        ecadImp.hide();
        poiImp.hide();

        // ---- Step 2: pick method --------------------------------------------------------
        String[] methods = {"Method 1: Junctional Threshold", "Method 2: 6 um Line"};
        GenericDialog gdMethod = new GenericDialog("Select Analysis Method");
        gdMethod.addChoice("Method:", methods, methods[0]);
        gdMethod.showDialog();
        if (gdMethod.wasCanceled()) return;
        String method = gdMethod.getNextChoice();

        // Ensure consistent global measurement settings (not strictly required since
        // we measure via ImageStatistics directly, but keeps Analyze > Measure in sync
        // if the user checks anything manually).
        IJ.run("Set Measurements...", "area mean integrated redirect=None decimal=3");

        if (method.equals(methods[0])) {
            String[] thresholdModes = {"Automatic", "Manual"};
            GenericDialog gdThresh = new GenericDialog("Mask Threshold Options");
            gdThresh.addMessage("Choose how each mask's threshold should be determined.");
            gdThresh.addChoice("Junction mask thresholding:", thresholdModes, thresholdModes[0]);
            gdThresh.addChoice("Nuclear mask thresholding:", thresholdModes, thresholdModes[0]);
            gdThresh.addMessage("Optional mask cleanup:\n"
                    + "Set to 0 to skip.");
            gdThresh.addNumericField("Junction mask - Dilation/erosion size to close holes:", 0, 0);
            gdThresh.addNumericField("Junction mask - Erosion/dilation size to remove small, isolated dots:", 0, 0);
            gdThresh.addNumericField("Nuclear mask - Dilation/erosion size to close holes:", 0, 0);
            gdThresh.addNumericField("Nuclear mask - Erosion/dilation size to remove small, isolated noise:", 0, 0);
            gdThresh.showDialog();
            if (gdThresh.wasCanceled()) return;
            boolean manualJunction = gdThresh.getNextChoice().equals("Manual");
            boolean manualNuclear = gdThresh.getNextChoice().equals("Manual");
            int junctionCloseIterations = (int) gdThresh.getNextNumber();
            int junctionOpenIterations = (int) gdThresh.getNextNumber();
            int nuclearCloseIterations = (int) gdThresh.getNextNumber();
            int nuclearOpenIterations = (int) gdThresh.getNextNumber();

            runMethod1JunctionalThreshold(dapiImp, ecadImp, poiImp, dir, manualJunction, manualNuclear, 
            		junctionCloseIterations, junctionOpenIterations, nuclearCloseIterations, nuclearOpenIterations);
        } else {
            runMethod2SixMicronLine(poiImp);
        }

        saveSummaryDialog(dir);
    }

    // =====================================================================================
    // METHOD 1: Junctional Threshold (+ Nuclear / Cytoplasmic compartments)
    // =====================================================================================
    private void runMethod1JunctionalThreshold(ImagePlus dapiImp, ImagePlus ecadImp, ImagePlus poiImp, String dir,
                                                boolean manualJunction, boolean manualNuclear,
                                                int junctionCloseIterations, int junctionOpenIterations,
                                                int nuclearCloseIterations, int nuclearOpenIterations) {

        // ---- Junction mask from the Ecad image ------------------------------------------
        ImagePlus junctionMaskImp = manualJunction
                ? createMaskFromImageManual(ecadImp, "JunctionMask")
                : createMaskFromImageAuto(ecadImp, "Default dark", "JunctionMask", false);
        if (junctionMaskImp == null) {
            IJ.error("Fluorescence Junction Analyzer", "Junction mask creation was cancelled or failed.");
            return;
        }
        applyErosionDilation(junctionMaskImp, junctionCloseIterations, junctionOpenIterations); 

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
        ImagePlus nuclearMaskImp = manualNuclear
                ? createMaskFromImageManual(dapiImp, "NuclearMask")
                : createMaskFromImageAuto(dapiImp, "Huang dark", "NuclearMask", true);
        double[] nuclear = new double[]{0, 0, 0, 0};
        if (nuclearMaskImp != null) {
            applyErosionDilation(nuclearMaskImp, nuclearCloseIterations, nuclearOpenIterations);
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

        // 8) Background selection: place an exact-area circle, let the user drag it.
        placeBackgroundOval(poiImp, BACKGROUND_CIRCLE_AREA);
        new WaitForUserDialog(
                "Background Selection",
                "A circular selection with an exact area of " + BACKGROUND_CIRCLE_AREA
                        + " (calibrated units^2) has been placed on " + poiImp.getTitle() + ".\n"
                        + "Drag it to a representative background region, then click OK."
        ).show();

        // 9) Measure the background region with the same four measurements.
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

        // 10) Add all measurements to the summary table.
        summary.incrementCounter();
        summary.addValue("POI Image", poiImp.getTitle());
        summary.addValue("Junction Area", junction[0]);
        summary.addValue("Junction IntDen", junction[1]);
        summary.addValue("Junction RawIntDen", junction[2]);
        summary.addValue("Junction Mean", junction[3]);
        summary.addValue("Nuclear Area", nuclear[0]);
        summary.addValue("Nuclear IntDen", nuclear[1]);
        summary.addValue("Nuclear RawIntDen", nuclear[2]);
        summary.addValue("Nuclear Mean", nuclear[3]);
        summary.addValue("Cytoplasmic Area", cytoplasmic[0]);
        summary.addValue("Cytoplasmic IntDen", cytoplasmic[1]);
        summary.addValue("Cytoplasmic RawIntDen", cytoplasmic[2]);
        summary.addValue("Cytoplasmic Mean", cytoplasmic[3]);
        summary.addValue("Background Area", background[0]);
        summary.addValue("Background IntDen", background[1]);
        summary.addValue("Background RawIntDen", background[2]);
        summary.addValue("Background Mean", background[3]);
        summary.addValue("Junctional/Cytoplasmic (Mean)", jcRatio);
        summary.addValue("Junctional/Nuclear (Mean)", jnRatio);
    }

    /**
     * Applies binary erosion followed by dilation to the given mask, using the
     * requested iteration counts. Eroding first removes small isolated dots/noise;
     * dilating afterward restores the size of the structures that survived. Either
     * count can be 0 to skip that step.
     */
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

    /**
     * Builds a mask from the given image using an automatic ImageJ threshold method
     * (e.g. "Default dark", "Huang dark"). Optionally applies a median filter
     * afterward (used for the nuclear/DAPI mask).
     */
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

    /**
     * Builds a mask from the given image by letting the user drag a slider and see
     * a live threshold preview, then confirm. Applies the same Despeckle / Dilate /
     * Fill Holes cleanup used by the original manual-threshold workflow. Returns
     * null if the user cancels.
     */
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

    /**
     * Cytoplasmic mask = NOT (nuclear mask OR junction mask), i.e. everything that is
     * neither nucleus nor junction.
     */
    private ImagePlus createCytoplasmicMask(ImagePlus nuclearMask, ImagePlus junctionMask) {
        ImageCalculator ic = new ImageCalculator();

        // 1. Union of nuclear and junction masks (areas to exclude)
        ImagePlus excludedMask = ic.run("Add create", nuclearMask, junctionMask);

        // 2. Invert the excluded mask to get the cytoplasmic mask
        ImageProcessor excludedIP = excludedMask.getProcessor();
        excludedIP.invert();
        excludedIP.resetMinAndMax();
        excludedIP.setBinaryThreshold();
        ImagePlus cytoplasmicMask = new ImagePlus("CytoplasmicMask", excludedIP);
        return cytoplasmicMask;
    }

    /**
     * Draws the given mask as a semi-transparent yellow overlay on the source image,
     * flattens it, and saves it as a TIFF in the given directory.
     */
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
    private void runMethod2SixMicronLine(ImagePlus imp) {

        RoiManager rm = getRoiManager();
        rm.reset();
        bringToFront(imp);

        ArrayList<String> roiTypeLabels = new ArrayList<>();

        for (int rep = 1; rep <= 3; rep++) {
            captureCalibratedLine(imp, rm, "junctional line (repeat " + rep + " of 3)", LINE_LENGTH_UM);
            roiTypeLabels.add("POI");

            captureCalibratedLine(imp, rm, "background line (repeat " + rep + " of 3)", LINE_LENGTH_UM);
            roiTypeLabels.add("Background");
        }

        // 8) Confirm all datapoints have been added.
        new WaitForUserDialog(
                "Confirm ROIs",
                "Have all datapoints been added to the ROI Manager? Click OK to proceed to measurement."
        ).show();

        // 9-10) Measure every ROI in the manager and label each pair (POI, Background).
        int count = rm.getCount();
        for (int i = 0; i < count; i++) {
            rm.select(imp, i);
            double[] meas = measureCurrentRoi(imp);

            String roiType = (i < roiTypeLabels.size()) ? roiTypeLabels.get(i) : (i % 2 == 0 ? "POI" : "Background");
            String pairLabel = "Pair " + ((i / 2) + 1);

            summary.incrementCounter();
            summary.addValue("Image", imp.getTitle());
            summary.addValue("Pair", pairLabel);
            summary.addValue("ROI Type", roiType);
            summary.addValue("Area", meas[0]);
            summary.addValue("IntDen", meas[1]);
            summary.addValue("RawIntDen", meas[2]);
            summary.addValue("Mean", meas[3]);
        }
    }

    /**
     * Prompts the user to draw a line and press 't' to add it to the ROI Manager,
     * rescales that line to an exact calibrated length (keeping its midpoint and
     * angle fixed), then lets the user reposition it without changing its length.
     */
    private void captureCalibratedLine(ImagePlus imp, RoiManager rm, String description, double lengthUm) {

        int countBefore = rm.getCount();
        new WaitForUserDialog(
                "Draw Line",
                "Draw the " + description + " and press 't' to add it to the ROI Manager.\nClick OK when done."
        ).show();

        if (rm.getCount() <= countBefore) {
            IJ.error("Fluorescence Junction Analyzer",
                    "No new ROI was added to the ROI Manager for the " + description + ". Please rerun.");
            return;
        }

        int index = rm.getCount() - 1;
        rm.select(imp, index);
        Roi roi = imp.getRoi();
        if (!(roi instanceof Line)) {
            IJ.error("Fluorescence Junction Analyzer",
                    "The most recently added ROI is not a line (" + description + ").");
            return;
        }

        adjustLineLength(imp, rm, index, lengthUm);

        // 3/6) Let the user fine-tune position without changing length.
        new WaitForUserDialog(
                "Confirm Position",
                "Confirm the " + description + " is in the correct position, then click OK.\n"
                        + "(You may reposition it without changing its length.)"
        ).show();

        // Persist any position change back into the ROI Manager entry.
        rm.select(imp, index);
        rm.runCommand("Update");
    }

    /**
     * Rescales a Line ROI already in the ROI Manager to an exact calibrated length,
     * using the image's pixel calibration (the same metadata the scale bar tool uses),
     * keeping the line's midpoint and angle unchanged.
     */
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

        Line newLine = new Line(newX1, newY1, newX2, newY2);
        imp.setRoi(newLine);
        rm.select(index);
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

    /**
     * Places an oval ROI on the image with an EXACT calibrated area, centered on the
     * image, ready for the user to drag to a background region.
     */
    private void placeBackgroundOval(ImagePlus imp, double calibratedArea) {
        bringToFront(imp);
        Calibration cal = imp.getCalibration();
        double pixelSize = (cal != null && cal.pixelWidth > 0) ? cal.pixelWidth : 1.0;

        double radiusCalibrated = Math.sqrt(calibratedArea / Math.PI);
        double radiusPixels = radiusCalibrated / pixelSize;
        double diameterPixels = radiusPixels * 2.0;

        double x = (imp.getWidth() / 2.0) - radiusPixels;
        double y = (imp.getHeight() / 2.0) - radiusPixels;

        OvalRoi oval = new OvalRoi(x, y, diameterPixels, diameterPixels);
        imp.setRoi(oval);
    }

    /**
     * Measures the current ROI on the given image and returns
     * {Area, IntDen, RawIntDen, Mean} in that order.
     * IntDen = calibrated Area x Mean. RawIntDen = pixel count x raw (uncalibrated) mean.
     */
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

    /**
     * Offers to save the summary table as CSV or Excel (.xls), defaulting to the
     * analyzed directory with the filename "analysis_summary" so that it is
     * discoverable by Combine_Analysis_Summaries, and always displays it as a
     * results window as well.
     */
    private void saveSummaryDialog(String defaultDir) {
        if (summary.size() == 0) {
            IJ.log("Fluorescence Junction Analyzer: no measurements were recorded.");
            return;
        }

        GenericDialog gd = new GenericDialog("Save Summary Table");
        gd.addMessage("Analysis complete. Save the summary table.");
        String[] formats = {"CSV (.csv)", "Excel (.xls)"};
        gd.addChoice("File format:", formats, formats[0]);
        gd.showDialog();

        summary.show("Fluorescence Junction Analyzer - Summary");

        if (gd.wasCanceled()) return;

        String choice = gd.getNextChoice();
        String extension = choice.startsWith("CSV") ? ".csv" : ".xls";

        SaveDialog sd = new SaveDialog("Save Summary Table", defaultDir, "analysis_summary", extension);
        String dir = sd.getDirectory();
        String name = sd.getFileName();
        if (dir == null || name == null) return;

        try {
            summary.save(dir + name);
            IJ.log("Summary table saved to " + dir + name);
        } catch (Exception e) {
            IJ.error("Fluorescence Junction Analyzer", "Error saving file: " + e.getMessage());
        }
    }
}