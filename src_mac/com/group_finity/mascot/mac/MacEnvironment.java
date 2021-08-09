package com.group_finity.mascot.mac;

import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.environment.Environment;
import com.group_finity.mascot.mac.jna.*;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Uses the apple Accessibility API  thought JNA to get environment information
 *
 * @author nonowarn
 */
class MacEnvironment extends Environment {
    //a lot of the comments here have been though google translate, it did a surprisingly good job tbh

    private static final Carbon carbon = Carbon.INSTANCE;

    /**
     * On Mac, you can take the active window,
     * Make the Mascot react to it.
     * <p>
     * So, in this class, give activeIE the alias frontmostWindow.
     */
    private static final Area activeIE = new Area();

    private static final Area frontmostWindow = activeIE;

    private static final int screenWidth = (int) Math.round(Toolkit.getDefaultToolkit().getScreenSize().getWidth());
    private static final int screenHeight = (int) Math.round(Toolkit.getDefaultToolkit().getScreenSize().getHeight());

    /**
     * the Shimeji program's PID
     */
    private static final long myPID =
            Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);

    /**
     * PID of the frontmost window's application
     */
    private static long currentPID = myPID;

    private static final HashSet<Long> touchedProcesses = new HashSet<>();


    static final CFStringRef kAXPosition = createCFString("AXPosition");
    static final CFStringRef kAXSize = createCFString("AXSize");
    static final CFStringRef kAXFocusedWindow = createCFString("AXFocusedWindow");
    static final CFStringRef kDock = createCFString("com.apple.Dock");
    static final CFStringRef kTileSize = createCFString("tilesize");
    static final CFStringRef kOrientation = createCFString("orientation");
    static final CFStringRef kAXChildren = createCFString("AXChildren");


    private static long getFrontmostAppsPID() {
        ProcessSerialNumber frontProcessPsn = new ProcessSerialNumber();
        LongByReference frontProcessPidp = new LongByReference();

        carbon.GetFrontProcess(frontProcessPsn);
        carbon.GetProcessPID(frontProcessPsn, frontProcessPidp);

        return frontProcessPidp.getValue();
    }


    private static Rectangle getFrontmostAppRect() {
        Rectangle rect;
        long pid = getCurrentPID();

        AXUIElementRef application =
                carbon.AXUIElementCreateApplication(pid);

        PointerByReference windowp = new PointerByReference();

        // XXX: Is error checking necessary other than here?
        if (carbon.AXUIElementCopyAttributeValue(application, kAXFocusedWindow, windowp) == carbon.kAXErrorSuccess) {
            AXUIElementRef window = new AXUIElementRef();
            window.setPointer(windowp.getValue());
            rect = getRectOfWindow(window);
        } else {
            rect = null;
        }

        carbon.CFRelease(application);
        return rect;
    }


    private static CGPoint getPositionOfWindow(AXUIElementRef window) {
        CGPoint position = new CGPoint();
        AXValueRef axvalue = new AXValueRef();
        PointerByReference valuep = new PointerByReference();

        carbon.AXUIElementCopyAttributeValue(window, kAXPosition, valuep);
        axvalue.setPointer(valuep.getValue());
        carbon.AXValueGetValue(axvalue, carbon.kAXValueCGPointType, position.getPointer());
        position.read();

        return position;
    }


    private static CGSize getSizeOfWindow(AXUIElementRef window) {
        CGSize size = new CGSize();
        AXValueRef axvalue = new AXValueRef();
        PointerByReference valuep = new PointerByReference();

        carbon.AXUIElementCopyAttributeValue(window, kAXSize, valuep);
        axvalue.setPointer(valuep.getValue());
        carbon.AXValueGetValue(axvalue, carbon.kAXValueCGSizeType, size.getPointer());
        size.read();

        return size;
    }


    private static void moveFrontmostWindow(final Point point) {
        AXUIElementRef application =
                carbon.AXUIElementCreateApplication(currentPID);

        PointerByReference windowp = new PointerByReference();

        if (carbon.AXUIElementCopyAttributeValue(
                application, kAXFocusedWindow, windowp) == carbon.kAXErrorSuccess) {
            AXUIElementRef window = new AXUIElementRef();
            window.setPointer(windowp.getValue());
            moveWindow(window, point.x, point.y);
        }

        carbon.CFRelease(application);
    }


    private static void restoreWindows() {
        Rectangle visibleArea = getWindowVisibleArea();
        for (long pid : getTouchedProcesses()) {
            AXUIElementRef application =
                    carbon.AXUIElementCreateApplication(pid);

            for (AXUIElementRef window : getWindowsOf(application)) {
                carbon.CFRetain(window);
                Rectangle windowRect = getRectOfWindow(window);
                if (!visibleArea.intersects(windowRect)) {
                    moveWindow(window, 0, 0);
                }
                carbon.CFRelease(window);
            }

            carbon.CFRelease(application);
        }
    }


    private static ArrayList<AXUIElementRef> getWindowsOf(AXUIElementRef application) {
        PointerByReference axWindowsp = new PointerByReference();
        ArrayList<AXUIElementRef> ret = new ArrayList<>();

        carbon.AXUIElementCopyAttributeValue(application, kAXChildren, axWindowsp);

        if (axWindowsp.getValue() == Pointer.NULL) {
            return ret;
        }

        CFArrayRef cfWindows = new CFArrayRef();
        cfWindows.setPointer(axWindowsp.getValue());

        for (long i = 0, l = carbon.CFArrayGetCount(cfWindows); i < l; ++i) {
            Pointer p = carbon.CFArrayGetValueAtIndex(cfWindows, i);
            AXUIElementRef el = new AXUIElementRef();
            el.setPointer(p);
            ret.add(el);
        }

        return ret;
    }

    private static Rectangle getRectOfWindow(AXUIElementRef window) {
        CGPoint pos = getPositionOfWindow(window);
        CGSize size = getSizeOfWindow(window);
        return new Rectangle(pos.getX(), pos.getY(), size.getWidth(), size.getHeight());
    }

    private static void moveWindow(AXUIElementRef window, int x, int y) {
        CGPoint position = new CGPoint((double) x, (double) y);
        position.write();
        AXValueRef axvalue = carbon.AXValueCreate(
                carbon.kAXValueCGPointType, position.getPointer());
        carbon.AXUIElementSetAttributeValue(window, kAXPosition, axvalue);
    }

    private static CFStringRef createCFString(String s) {
        return carbon.CFStringCreateWithCharacters(null, s.toCharArray(), s.length());
    }

    /**
     * When min < max,
     * Returns x if min <= x <= max
     * Returns min if x < min
     * Returns max if x> max
     */
    private static double betweenOrLimit(double x, double min, double max) {
        return Math.min(Math.max(x, min), max);
    }

    /**
     * On a Mac, trying to move a window completely off screen pushes it back into the screen. so...
     *
     * @return A rectangle where the window will still be visible on screen
     */
    private static Rectangle getWindowVisibleArea() {
        final int menuBarHeight = 22;//also y
        int x = 1;
        int width = getScreenWidth() - 2;
        int height = getScreenHeight() - menuBarHeight;

        refreshDockState();
        final String orientation = getDockOrientation();
        final int tilesize = getDockTileSize();

        if ("bottom".equals(orientation)) {
            height -= tilesize;
        } else if ("right".equals(orientation)) {
            width -= tilesize;
        } else if ("left".equals(orientation)) {
            x += tilesize;
            width -= tilesize;
        } else {
            // unknown
            x += tilesize;
            width -= 2 * tilesize;
        }

        return new Rectangle(x, menuBarHeight, width, height);
    }

    private static String getDockOrientation() {
        CFTypeRef orientationRef =
                carbon.CFPreferencesCopyValue(
                        kOrientation, kDock, carbon.kCurrentUser, carbon.kAnyHost);

        // I have an environment where CFPreferencesCopyValue returns null
        if (orientationRef == null) {
            return "null";
        }

        final int bufsize = 64;
        Memory buf = new Memory(64);
        carbon.CFStringGetCString(orientationRef, buf, bufsize, carbon.CFStringGetSystemEncoding());
        carbon.CFRelease(orientationRef);
        String ret = buf.getString(0, false);
        buf.clear();
        return ret;
    }

    /**
     * I can't find an efficient way to monitor the height of the Dock,
     * so I'll just return a constant larger than the maximum size of the Dock.
     * <p>
     * The value obtained with CFPreferencesCopyValue is different from the
     * value obtained with AppleScript,and AppleScript is the correct value.
     * <p>
     * If you get the pid and use the Accessibility API,
     * you can get the correct value, but if killall Dock is done, it will segfault.
     * I have to re-take the pid every time to avoid segfault, but I can't
     * find any other way than searching through the list of processes.
     * I don't want to use AppleScript because of how often it is called.
     * We will consider this trade-off later.
     */
    private static int getDockTileSize() {
        return 100;
    }

    private static void refreshDockState() {
        carbon.CFPreferencesAppSynchronize(kDock);
    }

    private static void setCurrentPID(long newPID) {
        if (newPID != myPID) {
            currentPID = newPID;
            getTouchedProcesses().add(newPID);
        }
    }

    private static long getCurrentPID() {
        return currentPID;
    }

    private static HashSet<Long> getTouchedProcesses() {
        return touchedProcesses;
    }

    private static int getScreenWidth() {
        return screenWidth;
    }

    private static int getScreenHeight() {
        return screenHeight;
    }

    private void updateFrontmostWindow() {
        final Rectangle frontmostWindowRect = getFrontmostAppRect();
        final Rectangle windowVisibleArea = getWindowVisibleArea();

        frontmostWindow.setVisible(
                (frontmostWindowRect != null)
                        && frontmostWindowRect.intersects(windowVisibleArea)
                        && !frontmostWindowRect.contains(windowVisibleArea) // Exclude desktop
        );
        frontmostWindow.set(
                frontmostWindowRect == null ? new Rectangle(-1, -1, 0, 0) : frontmostWindowRect);
    }

    private static void updateFrontmostApp() {
        long newPID = getFrontmostAppsPID();
        setCurrentPID(newPID);
    }

    @Override
    public void tick() {
        super.tick();
        updateFrontmostApp();
        this.updateFrontmostWindow();
    }

    /**
     * As mentioned above, if you try to move completely off the screen, you will be pushed back,
     * so switch to moving as much as possible for such a position specification.
     */
    @Override
    public void moveActiveIE(final Point point) {
        final Rectangle
                visibleRect = getWindowVisibleArea(),
                windowRect = getFrontmostAppRect();

        // Wrap coordinates to the left
        final double minX = visibleRect.getMinX() - windowRect.getWidth();
        // Wrap coordinates to the right
        final double maxX = visibleRect.getMaxX();

        // Upward wrapping coordinates (Cannot move above the menu bar)
        final double minY = visibleRect.getMinY();
        // Downward wrapping coordinates
        final double maxY = visibleRect.getMaxY();

        double pX = point.getX();
        double pY = point.getY();

        // Folding in the X direction
        pX = betweenOrLimit(pX, minX, maxX);

        // Folding in the Y direction
        pY = betweenOrLimit(pY, minY, maxY);

        point.setLocation(pX, pY);
        moveFrontmostWindow(point);
    }

    @Override
    public void restoreIE() {
        restoreWindows();
        getTouchedProcesses().clear();
    }

    @Override
    public Area getWorkArea() {
        return getScreen();
    }

    @Override
    public Area getActiveIE() {
        return activeIE;
    }

    @Override
    public void refreshCache() {}

}
