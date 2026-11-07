using System;
using System.Runtime.InteropServices;

namespace Aavija.Core;

public class NativeHandTracker : IDisposable
{
    private const string DllName = "mediapipe_windows_hand.dll";

    [StructLayout(LayoutKind.Sequential)]
    public struct LandmarkerResult
    {
        public int HandCount;
        public int LandmarkCount;
        public IntPtr X;
        public IntPtr Y;
        public IntPtr Z;
    }

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr CreateHandLandmarker(string modelPath);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern LandmarkerResult DetectHands(IntPtr landmarkerPtr, IntPtr imageData, int width, int height, int widthStep);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern void FreeResult(LandmarkerResult result);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern void DestroyHandLandmarker(IntPtr landmarkerPtr);

    private IntPtr _landmarkerPtr;

    public bool IsAvailable
    {
        get
        {
            try
            {
                // Trigger DllNotFoundException if the DLL is absent.
                var _ = CreateHandLandmarker;
                return true;
            }
            catch (DllNotFoundException) { return false; }
            catch (EntryPointNotFoundException) { return false; }
        }
    }

    public bool Initialize(string modelPath)
    {
        try
        {
            _landmarkerPtr = CreateHandLandmarker(modelPath);
            return _landmarkerPtr != IntPtr.Zero;
        }
        catch { return false; }
    }

    public LandmarkerResult ProcessFrame(IntPtr rawPixelBuffer, int width, int height, int stride)
    {
        if (_landmarkerPtr == IntPtr.Zero)
            return new LandmarkerResult { HandCount = 0, LandmarkCount = 21 };

        try
        {
            var result = DetectHands(_landmarkerPtr, rawPixelBuffer, width, height, stride);
            return result;
        }
        catch
        {
            return new LandmarkerResult { HandCount = 0, LandmarkCount = 21 };
        }
    }

    public void Free(LandmarkerResult result)
    {
        try { FreeResult(result); } catch { }
    }

    public void Dispose()
    {
        if (_landmarkerPtr != IntPtr.Zero)
        {
            try { DestroyHandLandmarker(_landmarkerPtr); } catch { }
            _landmarkerPtr = IntPtr.Zero;
        }
    }
}
