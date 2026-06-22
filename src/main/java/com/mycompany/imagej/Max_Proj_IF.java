package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.NonBlockingGenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.io.DirectoryChooser;
import java.io.File;

import ij.gui.GenericDialog; //additional imports
import java.util.ArrayList;
import java.util.List;

public class Max_Proj_IF implements PlugIn {

    @Override
    public void run(String arg) {

        // ── Step 1: options dialog ────────────────────────────────────────────
        // ADDED: ask for projection type and single vs batch mode before anything else
        GenericDialog optGd = new GenericDialog("Projection Options");
        optGd.addCheckbox("Batch mode (process all subfolders inside selected folder)", false);
        optGd.addMessage(" ");
        optGd.addCheckbox("Use Average projection  [default: Maximum]", false);
        optGd.addMessage("Maximum = standard for publication figures.\n"
                       + "Average = smoother appearance for diffuse markers,\n"
                       + "          reduces noise without clipping bright junctions.");
        optGd.showDialog();
        if (optGd.wasCanceled()) return;

        boolean batchMode  = optGd.getNextBoolean();
        boolean useAverage = optGd.getNextBoolean();
        // ZProjector method string — "avg" or "max"
        String projMethod  = useAverage ? "avg" : "max";

        // ── Step 2: directory selection ──────────────────────────────────────
        DirectoryChooser dc = new DirectoryChooser(
                batchMode ? "Select parent folder containing subfolders" : "Select image folder");
        String dir = dc.getDirectory();
        if (dir == null) {
            IJ.showMessage("No folder selected.");
            return;
        }

        // ── Step 3: build list of folders to process ─────────────────────────
        List<File> foldersToProcess = new ArrayList<>();
        if (batchMode) {
            // Collect all immediate subfolders of the selected parent
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

        // ── Step 4: slice selection using Junction channel as visual reference ─
        // Find a Junction_ image in the first valid folder so the user can see
        // exactly where the apical junctional complex sits in the z-stack.
        // AMENDED: the same slice range is applied to ALL folders in batch mode,
        // ensuring consistent z-position across conditions.
        File referenceJunction = null;
        for (File folder : foldersToProcess) {
            referenceJunction = findFileByPrefix(folder, "Junction_");
            if (referenceJunction != null) break;
        }
        if (referenceJunction == null) {
            IJ.showMessage("No image starting with 'Junction_' found in any folder.");
            return;
        }

        ImagePlus refImp = IJ.openImage(referenceJunction.getAbsolutePath());
        if (refImp == null) {
            IJ.showMessage("Failed to open reference Junction image.");
            return;
        }
        refImp.show();

        int stackSize = refImp.getStackSize();
        if (stackSize < 2) {
            IJ.showMessage("Junction image does not have multiple slices to project.");
            refImp.close();
            return;
        }

        NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Select Z-Range for Projection");
        gd.addMessage("Junction image is shown for reference.\n"
                    + "Scroll through slices to find where E-cadherin junctions\n"
                    + "are sharpest (apical junctional complex).");
        gd.addNumericField("First slice (>= 1):", 1, 0);
        gd.addNumericField("Last slice  (<= " + stackSize + "):", stackSize, 0);
        if (batchMode) {
            gd.addMessage("This range will be applied to ALL " + foldersToProcess.size() + " subfolders.");
        }
        gd.showDialog();
        if (gd.wasCanceled()) {
            refImp.close();
            return;
        }

        int firstSlice = Math.max(1,          (int) gd.getNextNumber());
        int lastSlice  = Math.min(stackSize,  (int) gd.getNextNumber());
        if (firstSlice > lastSlice) {
            IJ.showMessage("First slice must be less than or equal to last slice.");
            refImp.close();
            return;
        }
        refImp.hide();

        // ── Step 5: process each folder ──────────────────────────────────────
        List<String> successes = new ArrayList<>();
        List<String> failures  = new ArrayList<>();

        for (File folder : foldersToProcess) {
            boolean ok = processOneFolder(folder.getAbsolutePath(), firstSlice, lastSlice, projMethod);
            if (ok) successes.add(folder.getName());
            else    failures.add(folder.getName());
        }

        refImp.close();

        // ── Step 6: summary report ───────────────────────────────────────────
        // AMENDED: single summary dialog instead of one blocking popup per folder
        StringBuilder sb = new StringBuilder();
        sb.append("Projection type : ").append(useAverage ? "Average" : "Maximum").append("\n");
        sb.append("Slices used     : ").append(firstSlice).append(" to ").append(lastSlice).append("\n\n");
        if (!successes.isEmpty()) {
            sb.append("Completed (").append(successes.size()).append("):\n");
            for (String s : successes) sb.append("  \u2713 ").append(s).append("\n");
        }
        if (!failures.isEmpty()) {
            sb.append("\nFailed (").append(failures.size()).append("):\n");
            for (String f : failures) sb.append("  \u2717 ").append(f).append("\n");
            sb.append("\nSee Fiji Log window for details.");
        }
        IJ.showMessage("Processing complete", sb.toString());
    }

    // ── ADDED: processes a single folder — called once per folder in both single
    // and batch mode so the logic is never duplicated ────────────────────────
    private boolean processOneFolder(String dir, int firstSlice, int lastSlice, String projMethod) {

        File junctionFile = findFileByPrefix(new File(dir), "Junction_");
        if (junctionFile == null) {
            IJ.log("Skipped — no Junction_ file found in: " + dir);
            return false;
        }

        ImagePlus impJunction = IJ.openImage(junctionFile.getAbsolutePath());
        if (impJunction == null) {
            IJ.log("Failed to open Junction file: " + junctionFile.getName());
            return false;
        }

        ImagePlus projJunction = ZProjector.run(impJunction, projMethod, firstSlice, lastSlice);
        if (projJunction == null) {
            IJ.log("Projection failed for Junction: " + junctionFile.getName());
            return false;
        }
        projJunction.setTitle("MAX" + impJunction.getTitle());

        ImagePlus projDAPI = makeMaxProj(dir, "DAPI_", firstSlice, lastSlice, projMethod);
        ImagePlus projIF   = makeMaxProj(dir, "IF_",   firstSlice, lastSlice, projMethod);

        if (projDAPI == null || projIF == null) {
            IJ.log("Failed to project DAPI or IF in: " + dir);
            return false;
        }

        // Save to "Max Projections" subfolder — same as original behaviour
        File saveFolder = new File(dir, "Max Projections");
        if (!saveFolder.exists()) saveFolder.mkdir();

        IJ.saveAsTiff(projJunction, new File(saveFolder, projJunction.getShortTitle() + ".tif").getAbsolutePath());
        IJ.saveAsTiff(projDAPI,     new File(saveFolder, projDAPI.getShortTitle()     + ".tif").getAbsolutePath());
        IJ.saveAsTiff(projIF,       new File(saveFolder, projIF.getShortTitle()       + ".tif").getAbsolutePath());

        projJunction.show();
        projDAPI.show();
        projIF.show();

        IJ.log("Saved projections to: " + saveFolder.getAbsolutePath());
        return true;
    }

    // ── ADDED: finds the first image file in a folder whose name starts with
    // the given prefix. Eliminates the duplicate file-search loops that existed
    // in run() and makeMaxProj() ──────────────────────────────────────────────
    private File findFileByPrefix(File folder, String prefix) {
        if (!folder.isDirectory()) return null;
        File[] files = folder.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(prefix)) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".tif") || name.endsWith(".tiff") ||
                    name.endsWith(".jpg") || name.endsWith(".png")) {
                    return f;
                }
            }
        }
        return null;
    }

    // Original single-argument version kept for backwards compatibility —
    // delegates to the new version with "max" as default projection method
    public ImagePlus makeMaxProj(String dir, String searchStart, int firstSlice, int lastSlice) {
        return makeMaxProj(dir, searchStart, firstSlice, lastSlice, "max");
    }

    // AMENDED: added projMethod parameter so caller controls max vs average.
    // Replaced all IJ.showMessage() calls with IJ.log() — blocking dialogs
    // inside a helper method halt batch processing at every missing file.
    // Errors are collected in the log and shown in the summary at the end.
    public ImagePlus makeMaxProj(String dir, String searchStart,
                                  int firstSlice, int lastSlice, String projMethod) {
        if (dir == null || searchStart == null) {
            IJ.log("makeMaxProj: null directory or search string.");
            return null;
        }

        // AMENDED: use findFileByPrefix() instead of duplicating the search loop
        File targetFile = findFileByPrefix(new File(dir), searchStart);

        if (targetFile == null) {
            IJ.log("No image starting with '" + searchStart + "' found in: " + dir);
            return null;
        }

        ImagePlus imp = IJ.openImage(targetFile.getAbsolutePath());
        if (imp == null) {
            IJ.log("Failed to open image: " + targetFile.getName());
            return null;
        }

        int stackSize = imp.getStackSize();
        if (stackSize < 2) {
            IJ.log("Image does not have multiple slices: " + targetFile.getName());
            return null;
        }

        // Clamp slices to valid range
        if (firstSlice < 1)          firstSlice = 1;
        if (lastSlice > stackSize)   lastSlice  = stackSize;
        if (firstSlice > lastSlice) {
            IJ.log("Invalid slice range for: " + targetFile.getName());
            return null;
        }

        ImagePlus proj = ZProjector.run(imp, projMethod, firstSlice, lastSlice);
        proj.setTitle("MAX" + imp.getTitle());
        return proj;
    }
}