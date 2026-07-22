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

    private int[] resolveSliceRange(RangeMode rangeMode, int stackSize, int manualFirst, int manualLast) {
        if (rangeMode == RangeMode.WHOLE) {
            return new int[]{1, stackSize};
        } else {
            int first = Math.max(1, manualFirst);
            int last = Math.min(stackSize, manualLast);
            return new int[]{first, last};
        }
    }

    private String titleWithRange(ProjMethod projMethod, String originalTitle, int[] range) {
        String cleanTitle = originalTitle.replaceAll("(?i)\\.(tif|tiff|jpg|png)$", "");
        return projMethod.prefix() + "_" + range[0] + "-" + range[1] + "_" + cleanTitle;
    }

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
        if (junctionRange[0] > junctionRange[1]) {
            IJ.log("Invalid slice range for: " + junctionFile.getName());
            return false;
        }
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

        ImagePlus projMerged = makeProjection(dir, "Merged_", rangeMode, manualFirst, manualLast, projMethod);
        if (projMerged == null) {
            IJ.log("Failed to project Merged image in: " + dir);
            return false;
        }

        String folderSuffix = " (" + junctionRange[0] + "-" + junctionRange[1] + ")";
        File saveFolder = new File(dir, projMethod.prefix() + " PROJECTIONS" + folderSuffix);
        if (!saveFolder.exists()) saveFolder.mkdir();

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

    public ImagePlus makeProjection(String dir, String searchStart, RangeMode rangeMode,
                                     int manualFirst, int manualLast, ProjMethod projMethod) {
        // Validation
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

        // Projection
        ImagePlus proj = ZProjector.run(imp, projMethod.code(), firstSlice, lastSlice);
        proj.setTitle(titleWithRange(projMethod, imp.getTitle(), range));
        return proj;
    }
}