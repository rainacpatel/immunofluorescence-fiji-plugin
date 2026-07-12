package com.mycompany.imagej; //change here and pom.xml

import ij.*;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Z_Projection_IF implements PlugIn {

    // Slice-range strategy the user picks in the options dialog. Each constant
    // carries its own dialog label, so there's one place to add/rename a mode
    // instead of a separate String constant plus a manually-maintained array.
    private enum RangeMode {
        MANUAL_VIEW("Manually select range (view reference image)"),
        MANUAL("Manually select range (input all upfront)"),
        WHOLE("Whole stack");

        private final String label;

        RangeMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        static String[] labels() {
            RangeMode[] values = values();
            String[] out = new String[values.length];
            for (int i = 0; i < values.length; i++) out[i] = values[i].label;
            return out;
        }

        static RangeMode fromLabel(String label) {
            for (RangeMode m : values()) {
                if (m.label.equals(label)) return m;
            }
            throw new IllegalArgumentException("Unknown range mode: " + label);
        }
    }

    // Z-projection type. Each constant carries the ZProjector method code (the
    // exact strings ZProjector.run(...) accepts — "avg", "max", "sd" — see
    // ij.plugin.ZProjector docs) and the file-name prefix that reflects the actual
    // projection performed, so the saved output is never mislabeled "MAX" when it's
    // really an average or standard-deviation projection.
    private enum ProjMethod {
        MAXIMUM("Maximum Intensity", "max", "MAX"),
        AVERAGE("Average Intensity", "avg", "AVG"),
        SUM("Sum Intensity", "sum", "SUM"),
        STD_DEV("Standard Deviation", "sd", "STD");

        private final String label;
        private final String zProjectorCode;
        private final String filePrefix;

        ProjMethod(String label, String zProjectorCode, String filePrefix) {
            this.label = label;
            this.zProjectorCode = zProjectorCode;
            this.filePrefix = filePrefix;
        }

        @Override
        public String toString() {
            return label;
        }

        String code() {
            return zProjectorCode;
        }

        String prefix() {
            return filePrefix;
        }

        static String[] labels() {
            ProjMethod[] values = values();
            String[] out = new String[values.length];
            for (int i = 0; i < values.length; i++) out[i] = values[i].label;
            return out;
        }

        static ProjMethod fromLabel(String label) {
            for (ProjMethod m : values()) {
                if (m.label.equals(label)) return m;
            }
            throw new IllegalArgumentException("Unknown projection method: " + label);
        }
    }

    @Override
    public void run(String arg) {
        GenericDialog optGd = new GenericDialog("Projection Options");
        
        optGd.addMessage("── Projection settings ────────────────");
        optGd.addChoice("Projection method:", ProjMethod.labels(), ProjMethod.MAXIMUM.toString());
        optGd.addMessage("Maximum = standard for publication figures.\n"
                + "Average = smoother appearance for diffuse markers, reduces noise without clipping bright junctions.\n");
        optGd.addMessage(" ");
        optGd.addCheckbox("Batch mode (process all subfolders inside selected folder)", false);
        optGd.addMessage(" ");
        optGd.addChoice("Slice range:", RangeMode.labels(), RangeMode.MANUAL_VIEW.toString());
        optGd.addMessage("Whole stack = project every slice.\n"
                + "Manual (view reference) = scroll a reference image, per folder, to pick its range.\n"
                + "Manual (input upfront) = enter slice ranges directly, before any processing.");
        optGd.addMessage(" ");
        optGd.addMessage("── Saving settings ────────────────");
        optGd.addCheckbox("Save 8-bit images for analysis", true);
        optGd.addCheckbox("Save RGB images for visualization", false);
        optGd.showDialog();

        if (optGd.wasCanceled()) return;

        boolean batchMode = optGd.getNextBoolean();
        ProjMethod projMethod = ProjMethod.fromLabel(optGd.getNextChoice());
        RangeMode rangeMode = RangeMode.fromLabel(optGd.getNextChoice());
        boolean save8bit = optGd.getNextBoolean();
        boolean saveRGB = optGd.getNextBoolean();

        DirectoryChooser dc = new DirectoryChooser(
                batchMode ? "Select parent folder containing subfolders" : "Select image folder");
        String dir = dc.getDirectory();

        if (dir == null) {
            IJ.showMessage("No folder selected.");
            return;
        }

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

        // Slice ranges are resolved upfront, per folder, for the two manual modes.
        // "Whole stack" needs no upfront range — it's resolved per-image from each
        // stack's own size inside processOneFolder. In single-folder mode, both
        // collection methods below naturally run their prompt exactly once, since
        // foldersToProcess has only one entry.
        Map<File, int[]> folderRanges = new HashMap<>();
        if (rangeMode == RangeMode.MANUAL_VIEW) {
            if (!collectManualViewRanges(foldersToProcess, folderRanges)) return;
        } else if (rangeMode == RangeMode.MANUAL) {
            if (!collectManualInputRanges(foldersToProcess, batchMode, folderRanges)) return;
        }

        List<String> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (File folder : foldersToProcess) {
            int[] range = folderRanges.getOrDefault(folder, new int[]{-1, -1});
            boolean ok = processOneFolder(folder.getAbsolutePath(), rangeMode,
                    range[0], range[1], projMethod, batchMode, save8bit, saveRGB);

            String label = folder.getName();
            if (range[0] > 0 && range[1] > 0) {
                label += " (" + range[0] + "-" + range[1] + ")";
            }
            if (ok) successes.add(label);
            else failures.add(label);
        }

        showSummary(projMethod, rangeMode, successes, failures);
    }

    // Manual-view mode: for every folder, opens that folder's own Junction image so
    // the user can scroll to the apical junctional complex and pick a range specific
    // to that folder. Returns false (aborting the whole run) if the user cancels.
    private boolean collectManualViewRanges(List<File> folders, Map<File, int[]> outRanges) {
        for (File folder : folders) {
            File junctionFile = findFileByPrefix(folder, "Junction_");
            if (junctionFile == null) {
                IJ.log("Skipping range selection — no Junction_ file found in: " + folder.getAbsolutePath());
                continue;
            }

            ImagePlus refImp = IJ.openImage(junctionFile.getAbsolutePath());
            if (refImp == null) {
                IJ.log("Failed to open reference Junction image in: " + folder.getAbsolutePath());
                continue;
            }
            refImp.show();

            int stackSize = refImp.getStackSize();
            if (stackSize < 2) {
                IJ.log("Junction image does not have multiple slices in: " + folder.getAbsolutePath());
                refImp.close();
                continue;
            }

            NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Select Z-Range — " + folder.getName());
            gd.addMessage("Folder: " + folder.getName() + "\n"
                    + "Junction image is shown for reference.\n"
                    + "Scroll through slices to find where E-cadherin junctions\n"
                    + "are sharpest (apical junctional complex).");
            gd.addNumericField("First slice (>= 1):", 1, 0);
            gd.addNumericField("Last slice  (<= " + stackSize + "):", stackSize, 0);
            gd.showDialog();

            if (gd.wasCanceled()) {
                refImp.close();
                return false;
            }

            int first = Math.max(1, (int) gd.getNextNumber());
            int last = Math.min(stackSize, (int) gd.getNextNumber());
            refImp.close();

            if (first > last) {
                IJ.showMessage("First slice must be less than or equal to last slice for: " + folder.getName());
                return false;
            }

            outRanges.put(folder, new int[]{first, last});
        }
        return true;
    }

    // Manual-input mode: in single-folder mode, a simple one-off first/last dialog.
    // In batch mode, a single dialog lists first/last fields for every folder in
    // order, plus a checkbox to apply one shared range to all folders instead.
    // Returns false (aborting the whole run) if the user cancels.
    private boolean collectManualInputRanges(List<File> folders, boolean batchMode, Map<File, int[]> outRanges) {
        if (!batchMode) {
            File folder = folders.get(0);
            GenericDialog gd = new GenericDialog("Manual Slice Range");
            gd.addMessage("Folder: " + folder.getName());
            gd.addNumericField("First slice:", 1, 0);
            gd.addNumericField("Last slice:", 1, 0);
            gd.showDialog();

            if (gd.wasCanceled()) return false;

            int first = (int) gd.getNextNumber();
            int last = (int) gd.getNextNumber();
            outRanges.put(folder, new int[]{first, last});
            return true;
        }

        GenericDialog gd = new GenericDialog("Manual Slice Ranges (Batch)");
        gd.addCheckbox("Use the same slice range for all folders", false);
        gd.addMessage("If checked, only the 'All folders' fields below are used.");
        gd.addNumericField("All folders - First slice:", 1, 0);
        gd.addNumericField("All folders - Last slice:", 1, 0);
        gd.addMessage(" ");
        gd.addMessage("Per-folder ranges (used when the box above is unchecked):");
        for (File folder : folders) {
            gd.addNumericField(folder.getName() + " - First slice:", 1, 0);
            gd.addNumericField(folder.getName() + " - Last slice:", 1, 0);
        }
        gd.showDialog();

        if (gd.wasCanceled()) return false;

        boolean useSameForAll = gd.getNextBoolean();
        int allFirst = (int) gd.getNextNumber();
        int allLast = (int) gd.getNextNumber();

        for (File folder : folders) {
            int first = (int) gd.getNextNumber();
            int last = (int) gd.getNextNumber();
            outRanges.put(folder, useSameForAll ? new int[]{allFirst, allLast} : new int[]{first, last});
        }
        return true;
    }

    private void showSummary(ProjMethod projMethod, RangeMode rangeMode, List<String> successes, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Projection type : ").append(projMethod).append("\n");
        sb.append("Slice range mode: ").append(rangeMode).append("\n\n");

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

    // Resolves the [firstSlice, lastSlice] to use for a given stack size and range
    // mode. Both manual modes pass the folder's already-collected values straight
    // through (clamped to the actual stack size); whole-stack mode projects every
    // slice.
    private int[] resolveSliceRange(RangeMode rangeMode, int stackSize, int manualFirst, int manualLast) {
        if (rangeMode == RangeMode.WHOLE) {
            return new int[]{1, stackSize};
        } else {
            int first = Math.max(1, manualFirst);
            int last = Math.min(stackSize, manualLast);
            return new int[]{first, last};
        }
    }

    // Builds a projection title that embeds the projection method's own prefix
    // (MAX/AVG/STD — never hardcoded) and the resolved slice range, e.g.
    // "AVG_S2-4_IF_SomeImage". Strips any trailing image extension from the source
    // title first — same cleanup Process_IF_Images.changeAndRenameChannel() does —
    // so the ".tif" from the opened file doesn't end up duplicated in the saved
    // output name.
    private String titleWithRange(ProjMethod projMethod, String originalTitle, int[] range) {
        String cleanTitle = originalTitle.replaceAll("(?i)\\.(tif|tiff|jpg|png)$", "");
        return projMethod.prefix() + "_" + range[0] + "-" + range[1] + "_" + cleanTitle;
    }

    // Saves a projection as a TIFF, optionally converting it to RGB first — mirrors
    // Process_IF_Images.saveImage(). Single-channel projections carry a color LUT
    // rather than real RGB pixels; convertToRGB bakes that LUT color into the pixel
    // data itself, per slice, and prefixes the file name with "RGB_".
    private void saveProjection(ImagePlus img, String dir, boolean convertToRGB) {
        ImagePlus imp = img.duplicate();

        if (convertToRGB) {
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

        String title = imp.getTitle().replaceFirst("^DUP_", "");
        if (convertToRGB) {
            title = "RGB_" + title;
        }
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_");

        File outputFile = new File(dir, title + ".tif");
        boolean success = new FileSaver(imp).saveAsTiff(outputFile.getAbsolutePath());
        if (!success) {
            IJ.log("Failed to save: " + outputFile.getAbsolutePath());
        } else {
            IJ.log("Saved: " + outputFile.getAbsolutePath());
        }
    }

    // Processes a single folder — called once per folder in both single and batch
    // mode so the logic is never duplicated. For whole-stack mode, the actual range
    // is resolved here from each image's own stack size, so batch runs with
    // differing stack sizes between folders each get correct ranges rather than one
    // global range.
    private boolean processOneFolder(String dir, RangeMode rangeMode, int manualFirst, int manualLast,
                                      ProjMethod projMethod, boolean closeInBatch,
                                      boolean save8bit, boolean saveRGB) {
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

        int[] junctionRange = resolveSliceRange(rangeMode, impJunction.getStackSize(), manualFirst, manualLast);
        ImagePlus projJunction = ZProjector.run(impJunction, projMethod.code(), junctionRange[0], junctionRange[1]);
        if (projJunction == null) {
            IJ.log("Projection failed for Junction: " + junctionFile.getName());
            return false;
        }
        projJunction.setTitle(titleWithRange(projMethod, impJunction.getTitle(), junctionRange));

        ImagePlus projDAPI = makeProjection(dir, "DAPI_", rangeMode, manualFirst, manualLast, projMethod);
        ImagePlus projPOI = makeProjection(dir, "POI_", rangeMode, manualFirst, manualLast, projMethod);

        if (projDAPI == null || projPOI == null) {
            IJ.log("Failed to project DAPI or POI in: " + dir);
            return false;
        }

        // Projects the merged RGB composite directly from the already-merged
        // "Merged_*" TIFF, rather than re-merging the projected channels, so it
        // stays consistent with the saved, intensity-adjusted merge output. Treated
        // as critical, same as DAPI/IF, since the merged image is essential for
        // visualizing the data.
        ImagePlus projMerged = makeProjection(dir, "Merged_", rangeMode, manualFirst, manualLast, projMethod);
        if (projMerged == null) {
            IJ.log("Failed to project Merged image in: " + dir);
            return false;
        }

        // Slice range is embedded in the "Z Projections" folder name (using the
        // Junction channel's range as the folder's reference range) and in every
        // saved file name (using that image's own resolved range, via
        // titleWithRange), so ranges are traceable even if a channel's own stack
        // size ever differs from the Junction channel's.
        String folderSuffix = " (" + junctionRange[0] + "-" + junctionRange[1] + ")";
        File saveFolder = new File(dir, projMethod.prefix() + " PROJECTIONS" + folderSuffix);
        if (!saveFolder.exists()) saveFolder.mkdir();

        // The Junction/DAPI/IF projections are single-channel with a color LUT
        // attached (inherited from Process_IF_Images). "8-bit" saves them as-is;
        // "RGB" bakes the LUT color into real RGB pixels first, same as
        // Process_IF_Images does for its split channels. Merged is already an RGB
        // composite, so it's always saved once regardless of these checkboxes.
        if (save8bit) {
            saveProjection(projJunction, saveFolder.getAbsolutePath(), false);
            saveProjection(projDAPI, saveFolder.getAbsolutePath(), false);
            saveProjection(projPOI, saveFolder.getAbsolutePath(), false);
        }
        if (saveRGB) {
            saveProjection(projJunction, saveFolder.getAbsolutePath(), true);
            saveProjection(projDAPI, saveFolder.getAbsolutePath(), true);
            saveProjection(projPOI, saveFolder.getAbsolutePath(), true);
        }
        saveProjection(projMerged, saveFolder.getAbsolutePath(), false);

        // In batch mode, close all open image windows after saving — with many
        // subfolders, leaving multiple windows open per folder quickly clutters the
        // workspace. In single-folder mode, leave them open so the user can inspect
        // the result immediately.
        if (closeInBatch) {
            WindowManager.closeAllWindows();
        } else {
            projJunction.show();
            projDAPI.show();
            projPOI.show();
            projMerged.show();
        }

        IJ.log("Saved projections to: " + saveFolder.getAbsolutePath());
        return true;
    }

    // Finds the first image file in a folder whose name starts with the given
    // prefix. Shared by run() and makeProjection() so the search logic isn't
    // duplicated.
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
    // delegates to the new version, always as a Maximum Intensity projection
    // (matching its original, pre-refactor behavior). Takes fixed slice numbers
    // rather than a range mode.
    @Deprecated
    public ImagePlus makeMaxProj(String dir, String searchStart, int firstSlice, int lastSlice) {
        return makeProjection(dir, searchStart, RangeMode.MANUAL, firstSlice, lastSlice, ProjMethod.MAXIMUM);
    }

    // Replaces blocking IJ.showMessage() calls with IJ.log() — a blocking dialog
    // inside a helper method would halt batch processing at every missing file.
    // Errors are collected in the log and shown in the summary at the end. Range
    // is resolved from THIS image's own stack size.
    public ImagePlus makeProjection(String dir, String searchStart, RangeMode rangeMode,
                                     int manualFirst, int manualLast, ProjMethod projMethod) {
        if (dir == null || searchStart == null) {
            IJ.log("makeProjection: null directory or search string.");
            return null;
        }

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
        int lastSlice = range[1];
        if (firstSlice > lastSlice) {
            IJ.log("Invalid slice range for: " + targetFile.getName());
            return null;
        }

        ImagePlus proj = ZProjector.run(imp, projMethod.code(), firstSlice, lastSlice);
        proj.setTitle(titleWithRange(projMethod, imp.getTitle(), range));
        return proj;
    }
}