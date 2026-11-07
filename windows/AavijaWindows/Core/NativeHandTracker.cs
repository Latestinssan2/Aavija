using System;
using System.Runtime.InteropServices;

namespace Aavija.Core;

public class NativeHandTracker : IDisposable
{
    private IntPtr _handle = IntPtr.Zero;

    [DllImport("AavijaHandTracker.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr CreateTracker(string modelPath);

    [DllImport("AavijaHandTracker.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int ProcessFrame(IntPtr handle, byte[] rgb, int width, int height, long nowMs);

    [DllImport("AavijaHandTracker.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern void DestroyTracker(IntPtr handle);

    public bool IsAvailable
    {
        get
        {
            try { var _unused = CreateTracker; return true; }
            catch (DllNotFoundException) { return false; }
            catch (EntryPointNotFoundException) { return false; }
        }
    }

    public bool Initialize(string modelPath)
    {
        try
        {
            _handle = CreateTracker(modelPath);
            return _handle != IntPtr.Zero;
        }
        catch { return false; }
    }

    public int ProcessFrame(byte[] rgb, int width, int height, long nowMs)
    {
        if (_handle == IntPtr.Zero) return 0;
        try { return ProcessFrame(_handle, rgb, width, height, nowMs); }
        catch { return 0; }
    }

    public void Dispose()
    {
        if (_handle != IntPtr.Zero)
        {
            try { DestroyTracker(_handle); } catch { }
            _handle = IntPtr.Zero;
        }
    }
}
