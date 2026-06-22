package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import java.awt.Color;
import ij.process.ImageProcessor;
import ij.io.FileSaver;
import java.io.File;
import java.io.PrintWriter;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.Overlay;
import ij.process.ImageStatistics;
import ij.measure.*;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import ij.Prefs;
import ij.gui.DialogListener;
import java.awt.AWTEvent;
import ij.plugin.ImageCalculator;
import ij.process.Blitter;

public class Analyze_Intensity_manualDAPI implements PlugIn {
	
	private ImagePlus createCytoplasmicMask(
            ImagePlus nuclearMask,
            ImagePlus junctionMask) {

        ImageCalculator ic = new ImageCalculator();

        // 1. Union of nuclear and junction masks (areas to exclude)
        ImagePlus excludedMask = ic.run("Add create", nuclearMask, junctionMask);

        // 2. Invert the excluded mask to get cytoplasmic mask
        ImageProcessor excludedIP = excludedMask.getProcessor();
        excludedIP.invert();
        excludedIP.resetMinAndMax();
        excludedIP.setBinaryThreshold();
        ImagePlus cytoplasmicMask = new ImagePlus("Cytoplasm" , excludedIP);
        return cytoplasmicMask;

    }
    
    @Override
    public void run(String arg) {
        DirectoryChooser dc = new DirectoryChooser("Choose Image Directory");
        String dir = dc.getDirectory();
        if (dir == null) return; // user cancelled

        // list files
        File folder = new File(dir);
        String[] fileList = folder.list((d, name) -> {
            String lname = name.toLowerCase();
            return lname.endsWith(".tif") || lname.endsWith(".tiff") ||
                   lname.endsWith(".jpg") || lname.endsWith(".png");
        });

        if (fileList == null || fileList.length == 0) {
            IJ.error("No image files found in the directory.");
            return;
        }

        // defaults
        int defaultDAPI = 0, defaultJunction = 0, defaultIF = 0;
        for (int i = 0; i < fileList.length; i++) {
            String name = fileList[i];
            if (name.contains("DAPI_") && defaultDAPI == 0) defaultDAPI = i;
            if (name.contains("Junction_") && defaultJunction == 0) defaultJunction = i;
            if (name.contains("MAXIF_") && defaultIF == 0) defaultIF = i;
        }

        // dropdowns
        GenericDialog gd = new GenericDialog("Select Images");
        gd.addChoice("DAPI image:", fileList, fileList[defaultDAPI]);
        gd.addChoice("Junction image:", fileList, fileList[defaultJunction]);
        gd.addChoice("IF image:", fileList, fileList[defaultIF]);

        gd.showDialog();
        if (gd.wasCanceled()) return;

        // Get user selections
        String dapiFile = gd.getNextChoice();
        String junctionFile = gd.getNextChoice();
        String IFFile = gd.getNextChoice();
        
        ImagePlus impDAPI = IJ.openImage(dir + dapiFile);
        ImagePlus impJunction = IJ.openImage(dir + junctionFile);
        ImagePlus impIF = IJ.openImage(dir + IFFile);
        impDAPI.hide();
        impIF.hide();
        impJunction.hide();

        // make masks
        ImagePlus jMask = createMaskFromImage(impJunction);
        if (jMask == null) return;
        ImagePlus dMask = createMaskFromImage(impDapi);
        if (dMask = null) return;

        // cytoplasmic mask = whole cell - nucleus - junction
        ImagePlus cytoMask = createCytoplasmicMask(dMask, jMask);

        // measure intensities
        double junctionIntensity = measureIntensity(impIF, jMask);
        double nuclearIntensity  = measureIntensity(impIF, dMask);
        double cytoIntensity     = measureIntensity(impIF, cytoMask);

        // save overlays
        saveOverlay(impIF, jMask, dir);
        saveOverlay(impIF, dMask, dir);
        saveOverlay(impIF, cytoMask, dir);

        // save CSV
        boolean csvSaved = saveIntensityCSV(dir, junctionIntensity, nuclearIntensity, cytoIntensity);
        if (!csvSaved) {
            IJ.showMessage("Warning", "Failed to save summary CSV file.");
        }
        
        double junctionCytoplasmicRatio = (cytoIntensity > 0) ? junctionIntensity / cytoIntensity : Double.NaN;
        double junctionNuclearRatio     = (nuclearIntensity > 0) ? junctionIntensity / nuclearIntensity : Double.NaN;
        
        String juncStr   = String.format("%.3f", junctionIntensity);
        String nucStr    = String.format("%.3f", nuclearIntensity);
        String cytoStr   = String.format("%.3f", cytoIntensity);
        String jcRatioStr = String.format("%.3f", junctionCytoplasmicRatio);
        String jnRatioStr = String.format("%.3f", junctionNuclearRatio);
        // results dialog
        
        
        GenericDialog resultsDialog = new GenericDialog("Analysis Summary");
		resultsDialog.addMessage("Fluorescence Intensities (Mean):");
		resultsDialog.addMessage("Junctional:   " + juncStr);
		resultsDialog.addMessage("Nuclear:     " + nucStr);
		resultsDialog.addMessage("Cytoplasmic: " + cytoStr);
		
		resultsDialog.addMessage("");
		resultsDialog.addMessage("Ratios:");
		resultsDialog.addMessage("Junctional / Cytoplasmic = " + jcRatioStr);
		resultsDialog.addMessage("Junctional / Nuclear     = " + jnRatioStr);
	    resultsDialog.showDialog();
    }


    private double measureIntensity(ImagePlus source, ImagePlus mask) {
        Roi roi = ThresholdToSelection.run(mask);
        if (roi == null) return 0;
        source.setRoi(roi);
        ImageStatistics stats = source.getStatistics(Measurements.MEAN | Measurements.INTEGRATED_DENSITY | Measurements.AREA);
        return stats.mean; // mean
    }

    private boolean saveOverlay(ImagePlus pla, ImagePlus mask, String dir){
        Roi maskRoi = ThresholdToSelection.run(mask);
        if (maskRoi == null) return false;
        maskRoi.setFillColor(new Color(255, 255, 0, 128));
        maskRoi.setStrokeColor(Color.YELLOW);
        maskRoi.setStrokeWidth(3.0);   
        Overlay maskOverlay = new Overlay(maskRoi);

        pla.setOverlay(maskOverlay);
        ImagePlus flattened = pla.flatten();
        FileSaver fs = new FileSaver(flattened);
        boolean saved = fs.saveAsTiff(dir + "/overlay_" + mask.getShortTitle() + ".tif");
        flattened.show();
        return saved;
    }

    private ImagePlus createMaskFromImage(ImagePlus imp) {
        ImagePlus copy = imp.duplicate();
        copy.show();

        ImageProcessor ip = copy.getProcessor().duplicate();
        final ImageProcessor ip2 = copy.getProcessor().duplicate();
        final ImageProcessor ipOrig = copy.getProcessor().duplicate();

        GenericDialog gd = new GenericDialog("Set Min Threshold");
        gd.addMessage("Adjust threshold for mask generation.");
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
            return null;
        }

        int threshold = (int) gd.getNextNumber();
        ip.threshold(threshold);
        ip.setBinaryThreshold();
        copy.setProcessor(ip);
        copy.updateAndDraw();
        Prefs.blackBackground = true;
        IJ.run(copy, "Despeckle", ""); 
        IJ.run(copy, "Dilate", "");
        IJ.run(copy, "Fill Holes", "");
        copy.hide();
        return copy;
    }

    private boolean saveIntensityCSV(String dir, double junctional, double nuclear, double cytoplasmic) {
      try {
	        File csvFile = new File(dir, "analysis_summary.csv");
	        PrintWriter pw = new PrintWriter(csvFile);
	        pw.println("Measurement,Mean Fluorescence");
	        String juncStr   = String.format("%.3f", junctional);
	        String nucStr    = String.format("%.3f", nuclear);
	        String cytoStr   = String.format("%.3f", cytoplasmic);
	
	        // Ratios 
	        double jcRatio = (cytoplasmic > 0) ? junctional / cytoplasmic : Double.NaN;
	        double jnRatio = (nuclear > 0) ? junctional / nuclear : Double.NaN;
	
	        String jcRatioStr = String.format("%.3f", jcRatio);
	        String jnRatioStr = String.format("%.3f", jnRatio);
	
	        pw.println("Junctional," + juncStr);
	        pw.println("Nuclear," + nucStr);
	        pw.println("Cytoplasmic," + cytoStr);
	        pw.println("Junctional/Cytoplasmic," + jcRatioStr);
	        pw.println("Junctional/Nuclear," + jnRatioStr);
	        pw.close();
	        return true;
	    } catch (Exception e) {
	        IJ.log("Error saving CSV: " + e.getMessage());
	        return false;
	 }
}
}
