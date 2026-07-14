package com.mycompany.imagej; //change here and pom.xml

import ij.*;
import ij.io.DirectoryChooser;
import java.util.ArrayList;
import java.util.List;
import ij.plugin.PlugIn;
import ij.plugin.ChannelSplitter;
import ij.gui.GenericDialog;
import java.io.File;
import ij.io.FileSaver;
import ij.process.ImageProcessor;
import ij.process.ColorProcessor;
import ij.plugin.RGBStackMerge;
import ij.plugin.RGBStackConverter;
import ij.process.LUT;
import java.util.Arrays;



public class Process_IF_Images implements PlugIn {

    private int POIChannel;
    private int POIMin;
    private int POIMax;
    private String POIColor;
    private int junctionChannel;
    private int junctionMin;
    private int junctionMax;
    private String junctionColor;
    private int dapiChannel;
    private int dapiMin;
    private int dapiMax;
    private String dapiColor;
    private String saveDir;
    private boolean save8bit;
    private boolean saveRGB;
    private boolean shortenAndCleanTitle;

    private static final String[] COLOR_OPTIONS = {"Red", "Green", "Blue", "Gray", "Cyan", "Magenta", "Yellow"};

    @Override
    public void run(String arg) {
        int[] ids = WindowManager.getIDList();

        if (ids == null || ids.length == 0) {
            IJ.showMessage("No images open", "Please open at least one image stack.");
            return;
        }

        ImagePlus firstImage = WindowManager.getImage(ids[0]);
        int nChannels = (firstImage != null) ? firstImage.getNChannels() : 3;

        // Get user input for channel, display range, and saving settings
        GenericDialog gd = new GenericDialog("Select Channels & Display Range");
        gd.addMessage("── Channel assignment (1 to " + nChannels + ") ──────────────────");
        gd.addNumericField("Protein of Interest (POI) channel:", 1, 0);
        gd.addChoice("POI color:", COLOR_OPTIONS, "Red");
        gd.addNumericField("Junction channel:", 2, 0);
        gd.addChoice("Junction color:", COLOR_OPTIONS, "Green");
        gd.addNumericField("DAPI channel:", 3, 0);
        gd.addChoice("DAPI color:", COLOR_OPTIONS, "Blue");

        gd.addMessage(" ");
        gd.addMessage("── Display range — applied to ALL images ────────────────");
        gd.addMessage("Set Min/Max to match your acquisition bit depth (8-bit = 0-255).");
        gd.addSlider("POI Min",       0, 255,    0);
        gd.addSlider("POI Max",       0, 255, 255);
        gd.addSlider("Junction Min", 0, 255,    0);
        gd.addSlider("Junction Max", 0, 255, 255);
        gd.addSlider("DAPI Min",     0, 255,    0);
        gd.addSlider("DAPI Max",     0, 255, 255);
        
        gd.addMessage(" ");
        gd.addMessage("── Saving settings ────────────────");
        gd.addCheckbox("Save 8-bit images for analysis", true);
        gd.addCheckbox("Save RGB images for visualization", false);
        gd.addCheckbox("Shorten and remove spaces from file name", false);
        gd.showDialog();
        
        if (gd.wasCanceled()) return;

        POIChannel       = (int) gd.getNextNumber();
        POIColor         = gd.getNextChoice();
        junctionChannel = (int) gd.getNextNumber();
        junctionColor   = gd.getNextChoice();
        dapiChannel     = (int) gd.getNextNumber();
        dapiColor       = gd.getNextChoice();
        POIMin           = (int) gd.getNextNumber();
        POIMax           = (int) gd.getNextNumber();
        junctionMin     = (int) gd.getNextNumber();
        junctionMax     = (int) gd.getNextNumber();
        dapiMin         = (int) gd.getNextNumber();
        dapiMax         = (int) gd.getNextNumber();

        save8bit = gd.getNextBoolean();
        saveRGB = gd.getNextBoolean();
        shortenAndCleanTitle = gd.getNextBoolean();

        DirectoryChooser dirChooser = new DirectoryChooser("Select Folder to Save Images");
        saveDir = dirChooser.getDirectory();

        if (saveDir == null) {
            IJ.showMessage("No folder selected", "Image saving was cancelled.");
            return;
        }

        ArrayList<ImagePlus> openImages = new ArrayList<>();
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) continue;
            openImages.add(imp);
        }

        // Track per-image results so one bad image doesn't hide failures in
        // otherwise-successful images (or vice versa).
        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (ImagePlus imp : openImages) {
            String title = imp.getTitle();
            String newTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (shortenAndCleanTitle) {
            	newTitle = newTitle.replaceAll(".*\\.lif - ", "");
            	newTitle = newTitle.replaceAll("\\s", "");
            }
            imp.setTitle(newTitle); 

            File imageFolder = new File(saveDir, newTitle);
            if (!imageFolder.exists()) {
                imageFolder.mkdirs();
            }

            boolean ok = splitImage(imp, imageFolder.getAbsolutePath());
            if (ok) successes.add(newTitle); else failures.add(newTitle);
        }

        if (failures.isEmpty()) {
            WindowManager.closeAllWindows();
            IJ.showMessage("Complete", "All images processed and saved successfully.");
        } else {
            StringBuilder sb = new StringBuilder("Processed " + successes.size() + " of " + openImages.size() + " images.\n\n");
            sb.append("Failed:\n");
            for (String f : failures) sb.append("  \u2717 ").append(f).append("\n");
            sb.append("\nSee Fiji Log window for details.");
            IJ.showMessage("Complete with errors", sb.toString());
        }
    }

    private String saveImage(ImagePlus img, String dir, boolean OneChannelConvertToRGB) {
    	ImagePlus imp = img.duplicate();
    	
    	if (OneChannelConvertToRGB) {
    		ImageStack oldStack = imp.getStack();
    	    int width = imp.getWidth();
    	    int height = imp.getHeight();
    	    int size = oldStack.getSize();
    	    ImageStack newStack = new ImageStack(width, height);
    	    for (int i = 1; i <= size; i++) {
    	        ImageProcessor ip = oldStack.getProcessor(i);
    	        ColorProcessor cp = (ColorProcessor) ip.convertToRGB();
    	        newStack.addSlice(oldStack.getSliceLabel(i), cp);
    	    }
    	    imp.setStack(newStack);
    	}
        
    	String newTitle = imp.getTitle();
    	newTitle = newTitle.replaceFirst("^DUP_", "");
    	if (OneChannelConvertToRGB) {	
    		newTitle = "RGB_" + newTitle;
    	}
        newTitle = newTitle.replaceAll("[\\\\/:*?\"<>|]", "_");

        File outputFile = new File(dir, newTitle + ".tif");
        FileSaver fs = new FileSaver(imp);
        boolean success = fs.saveAsTiff(outputFile.getAbsolutePath());
        if (!success) {
            IJ.log("Failed to save: " + outputFile.getAbsolutePath());
        } else {
            IJ.log("Saved: " + outputFile.getAbsolutePath());
        }
        return newTitle;
    }

    private boolean splitImage(ImagePlus imp, String dir) {
        String imageTitle = imp.getTitle();
        int nChannels = imp.getNChannels();
        
        if (nChannels < 3) {
            IJ.showMessage("Error", imageTitle + " does not have at least 3 channels.");
            return false;
        }
        if (!isValidChannel(POIChannel, nChannels) ||
                !isValidChannel(dapiChannel, nChannels) ||
                !isValidChannel(junctionChannel, nChannels)) {
            IJ.showMessage("Error", imageTitle + ": One or more channel numbers are invalid.");
            return false;
        }
        if (POIChannel == dapiChannel || POIChannel == junctionChannel
                || dapiChannel == junctionChannel) {
            IJ.showMessage("Error", imageTitle + ": Each channel must be unique.");
            return false;
        }
        if (POIColor.equals(dapiColor) || POIColor.equals(junctionColor)
                || dapiColor.equals(junctionColor)) {
            IJ.showMessage("Error", imageTitle + ": Each channel must have a unique color.");
            return false;
        }

        ImagePlus[] channels = ChannelSplitter.split(imp);

        ImagePlus poiIm = changeAndRenameChannel(channels, POIChannel, imp.getTitle(), "POI", POIMin, POIMax);
        ImagePlus dapiIm = changeAndRenameChannel(channels, dapiChannel, imp.getTitle(), "DAPI", dapiMin, dapiMax);
        ImagePlus junctionIm = changeAndRenameChannel(channels, junctionChannel, imp.getTitle(), "Junction", junctionMin, junctionMax);

        applyLut(poiIm, POIColor);
        applyLut(junctionIm, junctionColor);
        applyLut(dapiIm, dapiColor);
        
        if (save8bit) {
        	saveImage(poiIm, dir, false);
        	saveImage(dapiIm, dir, false);
        	saveImage(junctionIm, dir, false);        	
        }
        
        if (saveRGB) {
        	saveImage(poiIm, dir, true);
        	saveImage(dapiIm, dir, true);
        	saveImage(junctionIm, dir, true);
        }

        ImagePlus[] mergeInput = buildMergeInput(poiIm, POIColor, junctionIm, junctionColor, dapiIm, dapiColor);

        ImagePlus merged = RGBStackMerge.mergeChannels(mergeInput, false);

        if (merged != null) {
            if (merged.isComposite()) {
                RGBStackConverter.convertToRGB(merged);
            }
            merged.setTitle("Merged_" + imp.getTitle());
            saveImage(merged, dir, false);
        } else {
            IJ.log("Failed to create merged image for: " + imp.getTitle());
            return false;
        }
        return true;
    }

    private boolean isValidChannel(int ch, int max) {
        return ch >= 1 && ch <= max;
    }

    private void applyLut(ImagePlus imp, String colorName) {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        for (int i = 0; i < 256; i++) {
            byte v = (byte) i;
            switch (colorName) {
                case "Red":     r[i] = v; break;
                case "Green":   g[i] = v; break;
                case "Blue":    b[i] = v; break;
                case "Gray":    r[i] = v; g[i] = v; b[i] = v; break;
                case "Cyan":    g[i] = v; b[i] = v; break;
                case "Magenta": r[i] = v; b[i] = v; break;
                case "Yellow":  r[i] = v; g[i] = v; break;
                default:
                    throw new IllegalArgumentException("Unknown color: " + colorName);
            }
        }
        LUT lut = new LUT(r, g, b);
        ImageProcessor ip = imp.getProcessor();
        ip.setLut(lut);
        imp.setProcessor(ip);
        imp.updateImage();
    }
    

    private ImagePlus[] buildMergeInput(ImagePlus im1, String color1,
                                        ImagePlus im2, String color2,
                                        ImagePlus im3, String color3) {
        ImagePlus[] slots = new ImagePlus[7]; // red, green, blue, gray, cyan, magenta, yellow
        placeInSlot(slots, im1, color1);
        placeInSlot(slots, im2, color2);
        placeInSlot(slots, im3, color3);
        return slots;
    }

    private void placeInSlot(ImagePlus[] slots, ImagePlus imp, String colorName) {
        int idx = Arrays.asList(COLOR_OPTIONS).indexOf(colorName);
        if (idx < 0) {
            throw new IllegalArgumentException("Unknown color: " + colorName);
        }
        slots[idx] = imp;
    }

    private ImagePlus changeAndRenameChannel(ImagePlus[] channels, int chIndex,
                                              String baseTitle, String label,
                                              int minNum, int maxNum) {
        ImagePlus ch = channels[chIndex - 1];
        ImageStack stack = ch.getStack();
        ImageStack rescaledStack = new ImageStack(stack.getWidth(), stack.getHeight());
        
        for (int slice = 1; slice <= stack.getSize(); slice++) {
        	ImageProcessor sliceIp = stack.getProcessor(slice);
            int[] lut = new int[256];
            for (int i = 0; i < 256; i++) {
                if (i <= minNum) {
                    lut[i] = 0;
                } else if (i >= maxNum) {
                    lut[i] = 255;
                } else {
                    lut[i] = (int) (((double)(i - minNum) / (maxNum - minNum)) * 255.0);
                }
            }
            sliceIp.applyTable(lut);
            sliceIp.setMinAndMax(0, 255);
            
            rescaledStack.addSlice(stack.getSliceLabel(slice), sliceIp.duplicate());
        }


        ch.setStack(rescaledStack);

        String cleanTitle = baseTitle.replaceAll("(?i)\\.(tif|tiff|jpg|png)$", "");
        
        ch.setTitle(label + "_" + minNum + "-" + maxNum + "_" + cleanTitle);
        return ch;
    }
}