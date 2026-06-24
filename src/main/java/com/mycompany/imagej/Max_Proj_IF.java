package com.mycompany.imagej; //change here and pom.xml

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.NonBlockingGenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.io.DirectoryChooser;
import java.io.File;

import ij.gui.GenericDialog; //additional imports
import java.util.ArrayList;
import java.util.List;

public class Max_Proj_IF implements PlugIn {

    private static final String RANGE_WHOLE  = "Whole stack";
    private static final String RANGE_MIDDLE = "Middle 3 slices";
    private static final String RANGE_MANUAL = "Manually select range (view reference image)";
    private static final String[] RANGE_MODES = {RANGE_WHOLE, RANGE_MIDDLE, RANGE_MANUAL};

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
        optGd.addMessage(" ");
        // ADDED: slice-range mode replaces always-manual entry. "Middle 3 slices"
        // is resolved per-folder (each folder's own stack size), so batch runs
        // with varying stack sizes between folders each get correct middle slices.
        optGd.addChoice("Slice range:", RANGE_MODES, RANGE_WHOLE);
        optGd.addMessage("Whole stack = project every slice.\n"
                       + "Middle 3 slices = each folder's own middle 3 (stack-size/2 -1 .. +1).\n"
                       + "Manual = scroll a reference image to pick one range for all folders.");
        optGd.showDialog();
        if (optGd.wasCanceled()) return;

        boolean batchMode  = optGd.getNextBoolean();
        boolean useAverage = optGd.getNextBoolean();
        String  rangeMode  = optGd.getNextChoice();
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

        // ── Step 4: slice selection ───────────────────────────────────────────
        // AMENDED: manual reference-image viewing (scrolling the Junction stack
        // to find the apical junctional complex) now only happens when the user
        // picked "Manual" slice-range mode. For "Whole stack" and "Middle 3
        // slices" there's nothing to look at or decide globally — those modes
        // are resolved per-folder in processOneFolder(), using each folder's own
        // image's stack size, which matters for "Middle 3" when stack sizes
        // differ between subfolders in batch mode.
        int firstSlice = -1; // -1 = "resolve per folder" sentinel
        int lastSlice  = -1;

        if (rangeMode.equals(RANGE_MANUAL)) {
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

            firstSlice = Math.max(1,         (int) gd.getNextNumber());
            lastSlice  = Math.min(stackSize, (int) gd.getNextNumber());
            if (firstSlice > lastSlice) {
                IJ.showMessage("First slice must be less than or equal to last slice.");
                refImp.close();
                return;
            }
            refImp.close();
        }

        // ── Step 5: process each folder ──────────────────────────────────────
        List<String> successes = new ArrayList<>();
        List<String> failures  = new ArrayList<>();

        for (File folder : foldersToProcess) {
            boolean ok = processOneFolder(folder.getAbsolutePath(), rangeMode,
                                           firstSlice, lastSlice, projMethod, batchMode);
            if (ok) successes.add(folder.getName());
            else    failures.add(folder.getName());
        }

        // ── Step 6: summary report ───────────────────────────────────────────
        // AMENDED: single summary dialog instead of one blocking popup per folder
        StringBuilder sb = new StringBuilder();
        sb.append("Projection type : ").append(useAverage ? "Average" : "Maximum").append("\n");
        sb.append("Slice range     : ").append(rangeMode);
        if (rangeMode.equals(RANGE_MANUAL)) {
            sb.append(" (").append(firstSlice).append(" to ").append(lastSlice).append(")");
        }
        sb.append("\n\n");
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

    // ADDED: resolves the actual [firstSlice, lastSlice] to use for a given
    // stack size and range mode. For Manual mode the caller already has fixed
    // values from the reference-image dialog, passed straight through here.
    // For Whole stack: 1..stackSize. For Middle 3: the 3 central slices,
    // clamped so it still works sensibly on stacks smaller than 3 slices.
    private int[] resolveSliceRange(String rangeMode, int stackSize,
                                     int manualFirst, int manualLast) {
        if (rangeMode.equals(RANGE_WHOLE)) {
            return new int[]{1, stackSize};
        } else if (rangeMode.equals(RANGE_MIDDLE)) {
            int mid = (stackSize + 1) / 2; // central slice, 1-based
            int first = mid - 1;
            int last  = mid + 1;
            if (first < 1) first = 1;
            if (last > stackSize) last = stackSize;
            return new int[]{first, last};
        } else { // RANGE_MANUAL
            int first = Math.max(1, manualFirst);
            int last  = Math.min(stackSize, manualLast);
            return new int[]{first, last};
        }
    }

    // ── ADDED: processes a single folder — called once per folder in both single
    // and batch mode so the logic is never duplicated ────────────────────────
    // AMENDED: now takes rangeMode instead of fixed slice numbers. For
    // "Whole stack" and "Middle 3 slices", the actual range is resolved here
    // from each image's own stack size — so batch runs with differing stack
    // sizes between folders (or even between channels within a folder) each
    // get correct, independently-computed ranges rather than one global range.
    private boolean processOneFolder(String dir, String rangeMode,
                                      int manualFirst, int manualLast, String projMethod,
                                      boolean closeInBatch) {

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

        int[] junctionRange = resolveSliceRange(rangeMode, impJunction.getStackSize(),
                                                 manualFirst, manualLast);
        ImagePlus projJunction = ZProjector.run(impJunction, projMethod,
                                                 junctionRange[0], junctionRange[1]);
        if (projJunction == null) {
            IJ.log("Projection failed for Junction: " + junctionFile.getName());
            return false;
        }
        projJunction.setTitle("MAX" + impJunction.getTitle());

        ImagePlus projDAPI = makeMaxProj(dir, "DAPI_", rangeMode, manualFirst, manualLast, projMethod);
        ImagePlus projIF   = makeMaxProj(dir, "IF_",   rangeMode, manualFirst, manualLast, projMethod);

        if (projDAPI == null || projIF == null) {
            IJ.log("Failed to project DAPI or IF in: " + dir);
            return false;
        }

        // ADDED: project the merged RGB composite too. This is done by running
        // ZProjector directly on the already-merged "Merged_*" TIFF rather than
        // re-merging the projected channels, so it stays consistent with the
        // saved, intensity-adjusted merge output. Treated as critical, same as
        // DAPI/IF — a missing or failed merged projection fails the whole
        // folder, since the merged image is essential for visualizing the data.
        ImagePlus projMerged = makeMaxProj(dir, "Merged_", rangeMode, manualFirst, manualLast, projMethod);
        if (projMerged == null) {
            IJ.log("Failed to project Merged image in: " + dir);
            return false;
        }

        // Save to "Max Projections" subfolder — same as original behaviour
        File saveFolder = new File(dir, "Max Projections");
        if (!saveFolder.exists()) saveFolder.mkdir();

        IJ.saveAsTiff(projJunction, new File(saveFolder, projJunction.getShortTitle() + ".tif").getAbsolutePath());
        IJ.saveAsTiff(projDAPI,     new File(saveFolder, projDAPI.getShortTitle()     + ".tif").getAbsolutePath());
        IJ.saveAsTiff(projIF,       new File(saveFolder, projIF.getShortTitle()       + ".tif").getAbsolutePath());
        IJ.saveAsTiff(projMerged,   new File(saveFolder, projMerged.getShortTitle()   + ".tif").getAbsolutePath());

        // ADDED: in batch mode, close all open image windows after saving —
        // with many subfolders, leaving multiple image windows open per folder
        // quickly clutters the workspace. In single-folder mode, leave them
        // open as before so the user can inspect the result immediately.
        if (closeInBatch) {
            WindowManager.closeAllWindows();
        } else {
            projJunction.show();
            projDAPI.show();
            projIF.show();
            projMerged.show();
        }

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
    // delegates to the new version with "max" as default projection method.
    // DEPRECATED: takes fixed slice numbers rather than a range mode, so
    // "Middle 3 slices" can't be resolved per-image through this entry point.
    @Deprecated
    public ImagePlus makeMaxProj(String dir, String searchStart, int firstSlice, int lastSlice) {
        return makeMaxProj(dir, searchStart, RANGE_MANUAL, firstSlice, lastSlice, "max");
    }

    // AMENDED: added projMethod parameter so caller controls max vs average.
    // Replaced all IJ.showMessage() calls with IJ.log() — blocking dialogs
    // inside a helper method halt batch processing at every missing file.
    // Errors are collected in the log and shown in the summary at the end.
    // AMENDED: now takes rangeMode + manualFirst/manualLast instead of fixed
    // firstSlice/lastSlice, so "Whole stack" and "Middle 3 slices" are resolved
    // from THIS image's own stack size.
    public ImagePlus makeMaxProj(String dir, String searchStart, String rangeMode,
                                  int manualFirst, int manualLast, String projMethod) {
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

        int[] range = resolveSliceRange(rangeMode, stackSize, manualFirst, manualLast);
        int firstSlice = range[0];
        int lastSlice  = range[1];
        if (firstSlice > lastSlice) {
            IJ.log("Invalid slice range for: " + targetFile.getName());
            return null;
        }

        ImagePlus proj = ZProjector.run(imp, projMethod, firstSlice, lastSlice);
        proj.setTitle("MAX" + imp.getTitle());
        return proj;
    }
}