package com.mycompany.imagej; //change here and pom.xml

import ij.*;
import ij.io.DirectoryChooser;
import java.util.ArrayList;
import ij.plugin.PlugIn;
import ij.plugin.ChannelSplitter;
import ij.gui.GenericDialog;
import java.io.File;
import ij.io.FileSaver;
import ij.process.ImageProcessor;
import ij.IJ;
import ij.plugin.RGBStackMerge;
import ij.process.LUT;
import java.awt.image.IndexColorModel;
import java.util.Arrays;



public class Process_IF_Images implements PlugIn {

    private int IFChannel;
    private int IFMin;          // ADDED: global display minimum for IF channel
    private int IFMax;
    private int junctionChannel;
    private int junctionMin;    // ADDED: global display minimum for Junction channel
    private int junctionMax;
    private int dapiChannel;
    private int dapiMin;        // ADDED: global display minimum for DAPI channel
    private int dapiMax;
    private String saveDir;

    public void run(String arg) {
        int[] ids = WindowManager.getIDList();

        if (ids == null || ids.length == 0) {
            IJ.showMessage("No images open", "Please open at least one image stack.");
            return;
        }

        // AMENDED: read actual channel count from the first open image
        // instead of hardcoding 3 — supports 4+ channel images in future
        ImagePlus firstImage = WindowManager.getImage(ids[0]);
        int nChannels = (firstImage != null) ? firstImage.getNChannels() : 3;

        GenericDialog gd = new GenericDialog("Select Channels & Display Range");

        // Section 1: channel assignment
        gd.addMessage("── Channel assignment (1 to " + nChannels + ") ──────────────────");
        gd.addNumericField("Protein / IF channel:", 1, 0);
        gd.addNumericField("Junction channel:", 2, 0);
        gd.addNumericField("DAPI channel:", 3, 0);

        // Section 2: global LUT
        // AMENDED: display range set once, applied identically to every image
        // in the batch. Brightness differences reflect biology not auto-scaling.
        // Slider ceiling raised to 65535 for 12-bit and 16-bit confocal images.
        gd.addMessage(" ");
        gd.addMessage("── Display range — applied to ALL images ────────────────");
        gd.addMessage("Set Min/Max to match your acquisition bit depth.");
        gd.addMessage("Typical: 8-bit = 0-255, 12-bit = 0-4095,  16-bit = 0-65535");
        gd.addSlider("IF Min",       0, 65535,    0);
        gd.addSlider("IF Max",       0, 65535, 255);
        gd.addSlider("Junction Min", 0, 65535,    0);
        gd.addSlider("Junction Max", 0, 65535, 255);
        gd.addSlider("DAPI Min",     0, 65535,    0);
        gd.addSlider("DAPI Max",     0, 65535, 255);
        gd.showDialog();

        if (gd.wasCanceled()) return;

        // Read channel assignments first, then Min/Max pairs
        // in the same order they were added to the dialog above
        IFChannel       = (int) gd.getNextNumber();
        junctionChannel = (int) gd.getNextNumber();
        dapiChannel     = (int) gd.getNextNumber();
        IFMin           = (int) gd.getNextNumber();
        IFMax           = (int) gd.getNextNumber();
        junctionMin     = (int) gd.getNextNumber();
        junctionMax     = (int) gd.getNextNumber();
        dapiMin         = (int) gd.getNextNumber();
        dapiMax         = (int) gd.getNextNumber();

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

        boolean suc = true;
        for (ImagePlus imp : openImages) {
            String title = imp.getTitle();
            String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");

            File imageFolder = new File(saveDir, safeTitle);
            if (!imageFolder.exists()) {
                imageFolder.mkdirs();
            }

            saveImage(imp, imageFolder.getAbsolutePath(), true);
            suc = splitImage(imp, imageFolder.getAbsolutePath());
        }

        if (suc) {
            WindowManager.closeAllWindows();
            IJ.showMessage("Complete", "All images processed and saved successfully.");
        } else {
            IJ.showMessage("Complete", "Error processing one or more files, see log");
        }
    }

    private String saveImage(ImagePlus imp, String dir, boolean original) {
        String title = imp.getTitle();
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (original) {
            safeTitle = "Original_" + safeTitle;
        }
        File outputFile = new File(dir, safeTitle + ".tif");
        FileSaver fs = new FileSaver(imp);
        boolean success = fs.saveAsTiff(outputFile.getAbsolutePath());
        if (!success) {
            IJ.log("Failed to save: " + outputFile.getAbsolutePath());
        } else {
            IJ.log("Saved: " + outputFile.getAbsolutePath());
        }
        return safeTitle;
    }

    private boolean splitImage(ImagePlus imp, String dir) {
        String imageTitle = imp.getTitle();
        int nChannels = imp.getNChannels();
        if (nChannels < 3) {
            IJ.showMessage("Error", imageTitle + " does not have at least 3 channels.");
            return false;
        }

        if (!isValidChannel(IFChannel, nChannels) ||
                !isValidChannel(dapiChannel, nChannels) ||
                !isValidChannel(junctionChannel, nChannels)) {
            IJ.showMessage("Error", imageTitle + ": One or more channel numbers are invalid.");
            return false;
        }

        if (IFChannel == dapiChannel || IFChannel == junctionChannel
                || dapiChannel == junctionChannel) {
            IJ.showMessage("Error", imageTitle + ": Each channel must be unique.");
            return false;
        }

        ImagePlus[] channels = ChannelSplitter.split(imp);

        // AMENDED: pass both Min and Max for consistent global LUT across batch
        ImagePlus plaIm      = changeAndRenameChannel(channels, IFChannel,
                                   imp.getTitle(), "IF",       IFMin,       IFMax);
        ImagePlus dapiIm     = changeAndRenameChannel(channels, dapiChannel,
                                   imp.getTitle(), "DAPI",     dapiMin,     dapiMax);
        ImagePlus junctionIm = changeAndRenameChannel(channels, junctionChannel,
                                   imp.getTitle(), "Junction", junctionMin, junctionMax);

        saveImage(plaIm,      dir, false);
        saveImage(dapiIm,     dir, false);
        saveImage(junctionIm, dir, false);

        IJ.run(plaIm,      "Green", "");
        IJ.run(junctionIm, "Red",   "");
        IJ.run(dapiIm,     "Blue",  "");

        ImagePlus merged = RGBStackMerge.mergeChannels(
                new ImagePlus[]{plaIm, junctionIm, dapiIm}, false);

        if (merged != null) {
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

    // AMENDED: added minNum parameter for full Min-Max display range
    // FIX: was label+"cleanTitle" — "cleanTitle" was a string literal, not the variable.
    // Every output was named e.g. "IF_cleanTitle.tif" breaking all downstream
    // file discovery by prefix matching.
    private ImagePlus changeAndRenameChannel(ImagePlus[] channels, int chIndex,
                                              String baseTitle, String label,
                                              int minNum, int maxNum) {
        ImagePlus ch = channels[chIndex - 1];
        ImageProcessor ip = ch.getProcessor();

        ip.setMinAndMax(minNum, maxNum);
        ip.snapshot();
        ip = ip.convertToByte(true);
        ch.setProcessor(ip);

        String cleanTitle = baseTitle.replaceAll("(?i)\\.(tif|tiff|jpg|png)$", "");
        ch.setTitle(label + "_" + cleanTitle);
        return ch;
    }
}