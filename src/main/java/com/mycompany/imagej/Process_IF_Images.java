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
import ij.plugin.RGBStackConverter;
import ij.process.LUT;
import java.awt.image.IndexColorModel;
import java.util.Arrays;



public class Process_IF_Images implements PlugIn {

    private int IFChannel;
    private int IFMin;          // ADDED: global display minimum for IF channel
    private int IFMax;
    private String IFColor;     // ADDED: user-assigned display color for IF channel
    private int junctionChannel;
    private int junctionMin;    // ADDED: global display minimum for Junction channel
    private int junctionMax;
    private String junctionColor; // ADDED: user-assigned display color for Junction channel
    private int dapiChannel;
    private int dapiMin;        // ADDED: global display minimum for DAPI channel
    private int dapiMax;
    private String dapiColor;   // ADDED: user-assigned display color for DAPI channel
    private String saveDir;

    private static final String[] COLOR_OPTIONS = {"Red", "Green", "Blue", "Gray", "Cyan", "Magenta", "Yellow"};

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
        gd.addChoice("IF color:", COLOR_OPTIONS, "Green");
        gd.addNumericField("Junction channel:", 2, 0);
        gd.addChoice("Junction color:", COLOR_OPTIONS, "Red");
        gd.addNumericField("DAPI channel:", 3, 0);
        gd.addChoice("DAPI color:", COLOR_OPTIONS, "Blue");

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

        // Read fields in the exact order they were added to the dialog above:
        // channel number, then color choice, for each of IF/Junction/DAPI,
        // followed by the six Min/Max sliders.
        IFChannel       = (int) gd.getNextNumber();
        IFColor         = gd.getNextChoice();
        junctionChannel = (int) gd.getNextNumber();
        junctionColor   = gd.getNextChoice();
        dapiChannel     = (int) gd.getNextNumber();
        dapiColor       = gd.getNextChoice();
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

        if (IFColor.equals(dapiColor) || IFColor.equals(junctionColor)
                || dapiColor.equals(junctionColor)) {
            IJ.showMessage("Error", imageTitle + ": Each channel must have a unique color.");
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

        // FIX: apply LUTs directly to each processor instead of IJ.run(imp, "Green", "").
        // IJ.run() color-LUT commands act on whatever ImageJ resolves as the "current"
        // image via the WindowManager, which is unreliable for off-screen images
        // produced by ChannelSplitter.split() (they're never shown/activated). That
        // caused the LUT to silently not apply, leaving channels grayscale and giving
        // RGBStackMerge nothing but gray LUTs to read color from when building the
        // composite — hence the black & white outputs and the failed-looking merge.
        //
        // ADDED: colors are now whatever the user picked in the dialog per channel,
        // instead of being hardcoded to IF=green/Junction=red/DAPI=blue. Acquisition
        // channel order varies between sessions, so the color has to be assignable.
        applyLut(plaIm,      IFColor);
        applyLut(junctionIm, junctionColor);
        applyLut(dapiIm,     dapiColor);

        // FIX: save colorized channel images AFTER the LUT is applied.
        // Previously these were saved before colorization, so the per-channel
        // TIFFs written to disk were always grayscale regardless of whether the
        // LUT commands worked.
        saveImage(plaIm,      dir, false);
        saveImage(dapiIm,     dir, false);
        saveImage(junctionIm, dir, false);

        // ADDED: build the merge input by color slot rather than assuming a fixed
        // IF/Junction/DAPI -> green/red/blue mapping. RGBStackMerge.mergeChannels
        // reads each ImagePlus's own LUT to decide its contribution, but still
        // expects positional c1=red, c2=green, c3=blue slots, so we sort our three
        // colorized images into the right positions based on what was picked.
        ImagePlus[] mergeInput = buildMergeInput(plaIm, IFColor,
                                                  junctionIm, junctionColor,
                                                  dapiIm, dapiColor);

        ImagePlus merged = RGBStackMerge.mergeChannels(mergeInput, false);

        if (merged != null) {
            // FIX: mergeChannels()/mergeHyperstacks() always returns a composite
            // hyperstack — channels stay a separate dimension, they are NOT
            // flattened into RGB pixels. Saved as-is, a 15-slice x 3-channel
            // composite writes out as 45 individual grayscale-with-LUT planes
            // (channels x slices), which is the "45 instead of 15, all looking
            // red" behavior. The manual recorder macro confirms the missing step:
            // it always follows "Merge Channels... create" with "RGB Color" to
            // flatten. RGBStackConverter.convertToRGB() is the equivalent direct
            // API call, and is safe to use with no window present (headless/batch).
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

    // Pure single/dual-channel LUTs matching ImageJ's built-in "Red"/"Green"/etc.
    // commands, applied directly to the processor so it doesn't depend on
    // WindowManager's "current image" resolution.
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

    // ADDED: RGBStackMerge.mergeChannels expects images positionally slotted as
    // {red, green, blue, gray, cyan, magenta, yellow}, with null for unused slots.
    // This places each of our three colorized channels into its correct slot
    // based on the color the user picked, rather than assuming a fixed mapping.
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

    // AMENDED: added minNum parameter for full Min-Max display range
    // FIX: was label+"cleanTitle" — "cleanTitle" was a string literal, not the variable.
    // Every output was named e.g. "IF_cleanTitle.tif" breaking all downstream
    // file discovery by prefix matching.
    // FIX: ch.getProcessor() only returns the processor for the CURRENTLY ACTIVE
    // slice of the stack. An earlier version called setMinAndMax()/convertToByte()
    // on that single processor only, then wrote it back with ch.setProcessor(ip) —
    // which also only replaces the active slice, leaving every other slice in a
    // multi-slice z-stack unadjusted.
    // FIX: a later version guarded the rescale behind `getBitDepth() != 8`, using
    // StackConverter.convertToGray8() to do the conversion. That guard was wrong:
    // when the source is already 8-bit, convertToGray8() does NOT rescale pixel
    // values to the given min/max — it only rescales when converting FROM 16-bit/
    // 32-bit/RGB. For already-8-bit sources the guard skipped rescaling entirely,
    // so the slider adjustment was set as processor metadata but never baked into
    // pixel values — explaining why the channel TIFF didn't visibly reflect a big
    // adjustment while the merge (which derives its contrast from the live display
    // range during the RGB-flatten step) did.
    // Now every slice is explicitly rebuilt via convertToByte(true) regardless of
    // starting bit depth, so the min/max is always actually baked into the saved
    // pixel data, for every slice.
    private ImagePlus changeAndRenameChannel(ImagePlus[] channels, int chIndex,
                                              String baseTitle, String label,
                                              int minNum, int maxNum) {
        ImagePlus ch = channels[chIndex - 1];
        ImageStack stack = ch.getStack();
        ImageStack rescaledStack = new ImageStack(stack.getWidth(), stack.getHeight());

        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor sliceIp = stack.getProcessor(slice);
            sliceIp.setMinAndMax(minNum, maxNum);
            ImageProcessor byteIp = sliceIp.convertToByte(true);
            rescaledStack.addSlice(stack.getSliceLabel(slice), byteIp);
        }

        ch.setStack(rescaledStack);

        String cleanTitle = baseTitle.replaceAll("(?i)\\.(tif|tiff|jpg|png)$", "");
        ch.setTitle(label + "_" + cleanTitle);
        return ch;
    }
}