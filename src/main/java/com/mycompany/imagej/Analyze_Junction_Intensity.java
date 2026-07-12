package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.util.ArrayList;

public class Analyze_Junction_Intensity  implements PlugIn {

    /** Target length, in micrometers, for Method 2 junctional/background lines. */
    private static final double LINE_LENGTH_UM = 6.0;

    /** Exact area, in calibrated units^2, for the Method 1 background circle. */
    private static final double BACKGROUND_CIRCLE_AREA = 39.136;

    private final ResultsTable summary = new ResultsTable();

    @Override
    public void run(String arg) {

        // ---- Step 1: pick open images -------------------------------------------------
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length < 2) {
            IJ.error("Fluorescence Junction Analyzer",
                    "At least two open images are required (E-cadherin and Protein of Interest).");
            return;
        }

        String[] titles = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            titles[i] = WindowManager.getImage(ids[i]).getTitle();
        }

        GenericDialog gdAssign = new GenericDialog("Assign Open Images");
        gdAssign.addChoice("E-cadherin image:", titles, titles[0]);
        gdAssign.addChoice("Protein of Interest image:", titles, titles[Math.min(1, titles.length - 1)]);
        gdAssign.showDialog();
        if (gdAssign.wasCanceled()) return;

        String ecadTitle = gdAssign.getNextChoice();
        String poiTitle = gdAssign.getNextChoice();
        if (ecadTitle.equals(poiTitle)) {
            IJ.error("Fluorescence Junction Analyzer", "Please select two different images.");
            return;
        }

        ImagePlus ecadImp = WindowManager.getImage(ecadTitle);
        ImagePlus poiImp = WindowManager.getImage(poiTitle);

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
            runMethod1JunctionalThreshold(ecadImp, poiImp);
        } else {
            runMethod2SixMicronLine(poiImp);
        }

        saveSummaryDialog();
    }

    // =====================================================================================
    // METHOD 1: Junctional Threshold
    // =====================================================================================
    private void runMethod1JunctionalThreshold(ImagePlus ecadImp, ImagePlus poiImp) {

        RoiManager rm = getRoiManager();
        rm.reset();

        // 1-2) Threshold the Ecad image automatically (no manual manipulation).
        IJ.setAutoThreshold(ecadImp, "Default dark");

        // 3) Create a selection from the threshold.
        IJ.run(ecadImp, "Create Selection", "");
        Roi thresholdRoi = ecadImp.getRoi();
        if (thresholdRoi == null) {
            IJ.error("Fluorescence Junction Analyzer",
                    "Thresholding did not produce a selection on " + ecadImp.getTitle() + ".");
            ecadImp.getProcessor().resetThreshold();
            return;
        }
        rm.addRoi(thresholdRoi);

        // 4) Turn the threshold selection into a mask.
        IJ.run(ecadImp, "Create Mask", "");
        ImagePlus maskImp = WindowManager.getImage("Mask");
        if (maskImp == null) {
            IJ.error("Fluorescence Junction Analyzer", "Mask creation failed.");
            ecadImp.getProcessor().resetThreshold();
            return;
        }

        // 5) Delete the selection from the ROI Manager so Fiji doesn't confuse it
        //    with selections belonging to a different image.
        rm.select(rm.getCount() - 1);
        rm.runCommand("Delete");
        ecadImp.deleteRoi();
        ecadImp.getProcessor().resetThreshold();

        // Create a clean selection from the mask itself, ready to transfer to the POI image.
        IJ.run(maskImp, "Create Selection", "");
        Roi maskRoi = maskImp.getRoi();
        if (maskRoi == null) {
            IJ.error("Fluorescence Junction Analyzer", "Could not create a selection from the mask.");
            maskImp.changes = false;
            maskImp.close();
            return;
        }

        // 6) Overlay the mask selection on top of the POI image.
        bringToFront(poiImp);
        poiImp.setRoi((Roi) maskRoi.clone());

        // Mask image no longer needed.
        maskImp.changes = false;
        maskImp.close();

        // 7) Measure junctional signal on the POI image (Area, IntDen, RawIntDen, Mean).
        double[] junction = measureCurrentRoi(poiImp);

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

        // 10) Add both sets of measurements to the summary table.
        summary.incrementCounter();
        summary.addValue("POI Image", poiImp.getTitle());
        summary.addValue("Junction Area", junction[0]);
        summary.addValue("Junction IntDen", junction[1]);
        summary.addValue("Junction RawIntDen", junction[2]);
        summary.addValue("Junction Mean", junction[3]);
        summary.addValue("Background Area", background[0]);
        summary.addValue("Background IntDen", background[1]);
        summary.addValue("Background RawIntDen", background[2]);
        summary.addValue("Background Mean", background[3]);
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
     * Offers to save the summary table as CSV or Excel (.xls), and always
     * displays it as a results window as well.
     */
    private void saveSummaryDialog() {
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

        SaveDialog sd = new SaveDialog("Save Summary Table", "Fluorescence_Summary", extension);
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
    

