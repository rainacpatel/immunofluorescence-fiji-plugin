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
    private int IFMax;
    private int junctionChannel;
    private int junctionMax;
    private int dapiChannel;
    private int dapiMax;
    private String saveDir;



    public void run(String arg) {
        int[] ids = WindowManager.getIDList();

        if (ids == null || ids.length == 0) {
            IJ.showMessage("No images open", "Please open at least one image stack.");
            return;
        }
        int nChannels = 3;
        GenericDialog gd = new GenericDialog("Select Channels");
        gd.addNumericField("Protein Channel (1 to " + nChannels + "):", 1, 0);
        gd.addSlider("Protein Max", 0, 255, 255);
        gd.addNumericField("Junction Channel (1 to " + nChannels + "):", 2, 0);
        gd.addSlider("Junction Max", 0, 255, 255);
        gd.addNumericField("DAPI Channel (1 to " + nChannels + "):", 3, 0);
        gd.addSlider("DAPI Max", 0, 255, 255);
        gd.showDialog();

        if (gd.wasCanceled()) return;

        IFChannel = (int) gd.getNextNumber();
        IFMax = (int) gd.getNextNumber();
        junctionChannel = (int) gd.getNextNumber();
        junctionMax = (int) gd.getNextNumber();
        dapiChannel = (int) gd.getNextNumber();
        dapiMax = (int) gd.getNextNumber();

        // Prompt the user to select a directory for saving images
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

            // Clean title to make it safe for folder names
            String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");

            // Create a folder for this image
            File imageFolder = new File(saveDir, safeTitle);
            if (!imageFolder.exists()) {
                imageFolder.mkdirs();
            }

            saveImage(imp, imageFolder.getAbsolutePath(), true);
            suc = splitImage(imp, imageFolder.getAbsolutePath());

        }
        if(suc){
            WindowManager.closeAllWindows();
            IJ.showMessage("Complete", "All images processed and saved successfully.");
        }
        else
            IJ.showMessage("Complete", "Error processing one or more files, see log");
    }

    private String saveImage(ImagePlus imp, String dir, boolean original){
        String title = imp.getTitle();
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        if(original){
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


    private boolean splitImage(ImagePlus imp, String dir){
        String imageTitle = imp.getTitle();
        int nChannels = imp.getNChannels();
        if (nChannels < 3) {
            IJ.showMessage("Error", imageTitle + " does not have at least 3 channels.");
            return false;
        }


        // Validate
        if (!isValidChannel(IFChannel, nChannels) ||
                !isValidChannel(dapiChannel, nChannels) ||
                !isValidChannel(junctionChannel, nChannels)) {
            IJ.showMessage("Error", imageTitle +  ": One or more channel numbers are invalid.");
            return false;
        }

        if (IFChannel == dapiChannel || IFChannel == junctionChannel || dapiChannel == junctionChannel) {
            IJ.showMessage("Error", imageTitle + ": Each channel must be unique.");
            return false;
        }

        // Split
        ImagePlus[] channels = ChannelSplitter.split(imp);
        ImagePlus plaIm = changeAndRenameChannel(channels, IFChannel, imp.getTitle(), "IF", IFMax);
        ImagePlus dapiIm = changeAndRenameChannel(channels, dapiChannel, imp.getTitle(), "DAPI", dapiMax);
        ImagePlus junctionIm = changeAndRenameChannel(channels, junctionChannel, imp.getTitle(), "Junction", junctionMax);
        saveImage(plaIm, dir, false);
        saveImage(dapiIm, dir, false);
        saveImage(junctionIm, dir, false);
        ImagePlus merged = RGBStackMerge.mergeChannels(new ImagePlus[] {plaIm, junctionIm, dapiIm}, false);

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

    private ImagePlus changeAndRenameChannel(ImagePlus[] channels, int chIndex, String baseTitle, String label, int maxNum) {
        ImagePlus ch = channels[chIndex - 1];
        ImageProcessor ip = ch.getProcessor();
        ip.setMinAndMax(0,maxNum);
        ch.setProcessor(ip);
        IJ.run(ch, "Apply LUT", "stack");
        ch.setTitle(label + "_" + baseTitle);
        return ch;
    }
}
