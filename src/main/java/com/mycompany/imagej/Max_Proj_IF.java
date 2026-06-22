package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.NonBlockingGenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.io.DirectoryChooser;
import java.io.File;

public class Max_Proj_IF implements PlugIn {

    @Override
    public void run(String arg) {
        // Ask user to select a directory
        DirectoryChooser dc = new DirectoryChooser("Select a folder");
        String dir = dc.getDirectory();
        if (dir == null) {
            IJ.showMessage("No folder selected.");
            return;
        }

        // Look for an image starting with "Junction"
        File folder = new File(dir);
        if (!folder.isDirectory()) {
            IJ.showMessage("Not a valid folder.");
            return;
        }

        File[] files = folder.listFiles();
        if (files == null) {
            IJ.showMessage("Failed to list files in folder.");
            return;
        }

        File junctionFile = null;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("Junction_")) {
                // Accept typical image extensions (can be expanded)
                String name = f.getName().toLowerCase();
                if (name.endsWith(".tif") || name.endsWith(".tiff") || name.endsWith(".jpg") || name.endsWith(".png")) {
                    junctionFile = f;
                    break;
                }
            }
        }

        if (junctionFile == null) {
            IJ.showMessage("No image starting with 'Junction_' found in the folder.");
            return;
        }

        // Open the image
        ImagePlus imp = IJ.openImage(junctionFile.getAbsolutePath());
        if (imp == null) {
            IJ.showMessage("Failed to open image: " + junctionFile.getName());
            return;
        }
        imp.show();

        int stackSize = imp.getStackSize();
        if (stackSize < 2) {
            IJ.showMessage("Image does not have multiple slices to project.");
            return;
        }

        // Ask user for first and last slice for max projection
        NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Max Projection Slices");
        gd.addNumericField("First slice (>=1):", 1, 0);
        gd.addNumericField("Last slice (<= " + stackSize + "):", stackSize, 0);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        int firstSlice = (int) gd.getNextNumber();
        int lastSlice = (int) gd.getNextNumber();

        if (firstSlice < 1) firstSlice = 1;
        if (lastSlice > stackSize) lastSlice = stackSize;
        if (firstSlice > lastSlice) {
            IJ.showMessage("First slice must be less or equal to last slice.");
            return;
        }

        imp.hide();

        //max projections
        ImagePlus maxJunction = ZProjector.run(imp, "max", firstSlice, lastSlice);
        maxJunction.setTitle("MAX" + imp.getTitle());
        ImagePlus maxDAPI = makeMaxProj(dir, "DAPI_", firstSlice, lastSlice);
        ImagePlus maxIF = makeMaxProj(dir, "IF_", firstSlice, lastSlice);

        if (maxJunction != null && maxDAPI != null && maxIF != null) {
            // Create "Max Projections" subfolder
            File saveFolder = new File(dir, "Max Projections");
            if (!saveFolder.exists()) {
                saveFolder.mkdir();
            }

            // Save each projection as TIFF
            IJ.saveAsTiff(maxJunction, new File(saveFolder, maxJunction.getShortTitle() + ".tif").getAbsolutePath());
            IJ.saveAsTiff(maxDAPI, new File(saveFolder, maxDAPI.getShortTitle() + ".tif").getAbsolutePath());
            IJ.saveAsTiff(maxIF, new File(saveFolder, maxIF.getShortTitle() + ".tif").getAbsolutePath());

            // Show projections
            maxJunction.show();
            maxDAPI.show();
            maxIF.show();

            IJ.showMessage("Processing complete",
                    "Max projection images have been created, displayed, and saved to:\n" + saveFolder.getAbsolutePath());
        } else {
            IJ.showMessage("Error",
                    "Error processing one or more images.\nPlease make sure the files are named correctly:\nIF_filename, DAPI_filename, & Junction_filename");
        }


    }

    public ImagePlus makeMaxProj(String dir, String searchStart, int firstSlice, int lastSlice) {
        if (dir == null || searchStart == null) {
            IJ.showMessage("Directory or search string is null.");
            return null;
        }

        File folder = new File(dir);
        if (!folder.isDirectory()) {
            IJ.showMessage("Not a valid folder.");
            return null;
        }

        File[] files = folder.listFiles();
        if (files == null) {
            IJ.showMessage("Failed to list files in folder.");
            return null;
        }

        File targetFile = null;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(searchStart)) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".tif") || name.endsWith(".tiff") || name.endsWith(".jpg") || name.endsWith(".png")) {
                    targetFile = f;
                    break;
                }
            }
        }

        if (targetFile == null) {
            IJ.showMessage("No image starting with '" + searchStart + "' found.");
            return null;
        }

        ImagePlus imp = IJ.openImage(targetFile.getAbsolutePath());
        if (imp == null) {
            IJ.showMessage("Failed to open image: " + targetFile.getName());
            return null;
        }

        int stackSize = imp.getStackSize();
        if (stackSize < 2) {
            IJ.showMessage("Image does not have multiple slices to project.");
            return null;
        }

        // Clamp slices to valid range
        if (firstSlice < 1) firstSlice = 1;
        if (lastSlice > stackSize) lastSlice = stackSize;
        if (firstSlice > lastSlice) {
            IJ.showMessage("First slice must be <= last slice.");
            return null;
        }

        ImagePlus maxProj = ZProjector.run(imp, "max", firstSlice, lastSlice);
        maxProj.setTitle("MAX" + imp.getTitle());
        return maxProj;
    }
}
