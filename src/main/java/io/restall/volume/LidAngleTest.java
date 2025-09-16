package io.restall.volume;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LidAngleTest {

    public interface CoreFoundationExt extends Library {
        CoreFoundationExt INSTANCE = Native.load("CoreFoundation", CoreFoundationExt.class);

        // Basic CF functions
        Pointer CFStringCreateWithCString(Pointer alloc, String cStr, int encoding);
        Pointer CFNumberCreate(Pointer allocator, int theType, IntByReference value);
        Pointer CFDictionaryCreateMutable(Pointer allocator, int capacity, Pointer keyCallbacks, Pointer valueCallbacks);

        void CFDictionarySetValue(Pointer dict, Pointer key, Pointer value);
        void CFRelease(Pointer cfObject);

        // Array functions
        int CFArrayGetCount(Pointer array);
        Pointer CFArrayGetValueAtIndex(Pointer array, int index);
        int CFSetGetCount(Pointer set);
        void CFSetGetValues(Pointer set, Pointer[] values); // fills an array of pointers

        // RunLoop
        Pointer CFRunLoopGetCurrent();
        void CFRunLoopRun();

        // Constants
        int kCFStringEncodingUTF8 = 0x08000100;
        int kCFNumberIntType = 3;
    }

    public interface IOKitHID extends Library {
        IOKitHID INSTANCE = Native.load("IOKit", IOKitHID.class);

        Pointer IOHIDManagerCreate(Pointer allocator, int options);
        void IOHIDManagerSetDeviceMatching(Pointer manager, Pointer matchingDict);
        int IOHIDManagerOpen(Pointer manager, int options);
        Pointer IOHIDManagerCopyDevices(Pointer manager);
        void IOHIDManagerClose(Pointer manager, int options);
        void IOHIDManagerScheduleWithRunLoop(Pointer manager, Pointer runLoop, Pointer runLoopMode);

        // Open/close device
        int IOHIDDeviceOpen(Pointer device, int options);
        void IOHIDDeviceClose(Pointer device, int options);

        // HID value / element functions
        Pointer IOHIDValueGetElement(Pointer value);
        int IOHIDElementGetUsagePage(Pointer element);
        int IOHIDElementGetUsage(Pointer element);
        int IOHIDValueGetIntegerValue(Pointer value);

        // Register input callback
        interface IOHIDValueCallback extends Callback {
            void callback(Pointer context, Pointer result, Pointer sender, Pointer value) throws IOException, InterruptedException;
        }

        void IOHIDDeviceRegisterInputValueCallback(Pointer device, IOHIDValueCallback callback, Pointer context);

        int IOHIDDeviceGetValue(Pointer device, Pointer element, PointerByReference value);
        Pointer IOHIDDeviceCopyMatchingElements(Pointer device, Pointer match, int options);
    }

    public static void main(String[] args) throws InterruptedException {
        CoreFoundationExt cf = CoreFoundationExt.INSTANCE;
        IOKitHID io = IOKitHID.INSTANCE;

        // Create matching dictionary
        Pointer matching = cf.CFDictionaryCreateMutable(null, 0, null, null);

        Pointer kVendor = cf.CFStringCreateWithCString(null, "VendorID", CoreFoundationExt.kCFStringEncodingUTF8);
        Pointer kProduct = cf.CFStringCreateWithCString(null, "ProductID", CoreFoundationExt.kCFStringEncodingUTF8);
        Pointer kUsagePage = cf.CFStringCreateWithCString(null, "PrimaryUsagePage", CoreFoundationExt.kCFStringEncodingUTF8);
        Pointer kUsage = cf.CFStringCreateWithCString(null, "PrimaryUsage", CoreFoundationExt.kCFStringEncodingUTF8);

        IntByReference vVendor = new IntByReference(0x5ac);
        Pointer nVendor = cf.CFNumberCreate(null, CoreFoundationExt.kCFNumberIntType, vVendor);
        IntByReference vProduct = new IntByReference(0x8104);
        Pointer nProduct = cf.CFNumberCreate(null, CoreFoundationExt.kCFNumberIntType, vProduct);
        IntByReference vUsagePage = new IntByReference(32);
        Pointer nUsagePage = cf.CFNumberCreate(null, CoreFoundationExt.kCFNumberIntType, vUsagePage);
        IntByReference vUsage = new IntByReference(138);
        Pointer nUsage = cf.CFNumberCreate(null, CoreFoundationExt.kCFNumberIntType, vUsage);

        cf.CFDictionarySetValue(matching, kVendor, nVendor);
        cf.CFDictionarySetValue(matching, kProduct, nProduct);
        cf.CFDictionarySetValue(matching, kUsagePage, nUsagePage);
        cf.CFDictionarySetValue(matching, kUsage, nUsage);

        // Create HID manager and set matching
        Pointer hidManager = io.IOHIDManagerCreate(Pointer.NULL, 0);
        io.IOHIDManagerSetDeviceMatching(hidManager, matching);
        Pointer kDefaultMode = CoreFoundationExt.INSTANCE.CFStringCreateWithCString(Pointer.NULL, "kCFRunLoopDefaultMode", CoreFoundationExt.kCFStringEncodingUTF8);
        io.IOHIDManagerScheduleWithRunLoop(hidManager, CoreFoundationExt.INSTANCE.CFRunLoopGetCurrent(), kDefaultMode);
        io.IOHIDManagerOpen(hidManager, 0);

        // Get devices (Should only be 1)
        Pointer devices = io.IOHIDManagerCopyDevices(hidManager);
        if (devices == null) {
            System.out.println("No lid-angle devices found.");
            return;
        }

        int count = cf.CFSetGetCount(devices);
        Pointer[] deviceArray = new Pointer[count];
        cf.CFSetGetValues(devices, deviceArray);

        // Register callback for each device
        IOKitHID.IOHIDValueCallback angleCallback = (context, result, sender, value) -> {
            Pointer element = io.IOHIDValueGetElement(value);
            int usagePage = io.IOHIDElementGetUsagePage(element);
            int usage = io.IOHIDElementGetUsage(element);

            // Likely different for different hardware
            if (usagePage == 0x20 && usage == 0x47F) { // lid-angle
                int angle = io.IOHIDValueGetIntegerValue(value);
                var volume = (float) angle / 1.3;
                setVolume((int) volume);
                System.out.printf("Volume: %s%n", (int) volume);
            }
        };

        for (Pointer device : deviceArray) {
            io.IOHIDDeviceOpen(device, 0);
            io.IOHIDDeviceRegisterInputValueCallback(device, angleCallback, null);
        }

        System.out.println("Running. Move or open/close the lid to see updates...");
        cf.CFRunLoopRun();
    }

    public static void setVolume(int level) throws IOException, InterruptedException {
        if (level < 0) level = 0;
        if (level > 100) level = 100;
        String script = "set volume output volume " + level;
        Process p = new ProcessBuilder("osascript", "-e", script).start();
        p.waitFor();
    }

    public static int getVolume() throws IOException, InterruptedException {
        String script = "output volume of (get volume settings)";
        Process p = new ProcessBuilder("osascript", "-e", script).start();
        var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = reader.readLine();
        p.waitFor();
        return Integer.parseInt(line.trim());
    }

}

