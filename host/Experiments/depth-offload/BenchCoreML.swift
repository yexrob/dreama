import Accelerate
import CoreImage
import CoreMedia
import CoreML
import CoreVideo
import Foundation
import Metal
import VideoToolbox

// Native macOS Core ML + VideoToolbox benchmark for Mac-side ZipDepth offload feasibility.
// Build & run:
//   swiftc -O BenchCoreML.swift -o build/bench-coreml \
//     -framework CoreML -framework CoreVideo -framework CoreMedia \
//     -framework VideoToolbox -framework CoreImage -framework Metal -framework Accelerate
//   ./build/bench-coreml build/zipdepth_256x256_fp16.mlpackage build/zipdepth_512x288_fp16.mlpackage build/zipdepth_640x384_fp16.mlpackage

struct TimingStats {
    let meanMs: Double
    let p50Ms: Double
    let p95Ms: Double
    let p99Ms: Double
    let fps: Double
}

func computeStats(_ samplesMs: [Double]) -> TimingStats {
    let sorted = samplesMs.sorted()
    let mean = sorted.reduce(0, +) / Double(sorted.count)
    func pct(_ p: Double) -> Double {
        let idx = min(sorted.count - 1, max(0, Int(Double(sorted.count - 1) * p)))
        return sorted[idx]
    }
    return TimingStats(meanMs: mean, p50Ms: pct(0.50), p95Ms: pct(0.95), p99Ms: pct(0.99), fps: 1000.0 / mean)
}

func nowMs() -> Double {
    Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000.0
}

func benchmarkModel(at packageURL: URL, units: MLComputeUnits, label: String, warmup: Int = 15, iters: Int = 100) throws -> TimingStats {
    let compiledURL = try MLModel.compileModel(at: packageURL)
    defer { try? FileManager.default.removeItem(at: compiledURL) }

    let cfg = MLModelConfiguration()
    cfg.computeUnits = units
    let model = try MLModel(contentsOf: compiledURL, configuration: cfg)

    guard let inputDesc = model.modelDescription.inputDescriptionsByName["rgb"],
          let constraint = inputDesc.multiArrayConstraint
    else {
        fatalError("Unexpected input description for \(packageURL.lastPathComponent)")
    }
    let shape = constraint.shape
    let arr = try MLMultiArray(shape: shape, dataType: .float32)
    let count = arr.count
    let ptr = arr.dataPointer.bindMemory(to: Float32.self, capacity: count)
    for i in 0..<count {
        ptr[i] = Float32(i % 255) / 255.0
    }
    let provider = try MLDictionaryFeatureProvider(dictionary: ["rgb": MLFeatureValue(multiArray: arr)])

    for _ in 0..<warmup {
        _ = try model.prediction(from: provider)
    }

    var times: [Double] = []
    times.reserveCapacity(iters)
    for _ in 0..<iters {
        let t0 = nowMs()
        _ = try model.prediction(from: provider)
        let t1 = nowMs()
        times.append(t1 - t0)
    }
    let stats = computeStats(times)
    print(String(
        format: "  %-28s [%-12s] mean=%5.2f ms  p50=%5.2f ms  p95=%5.2f ms  p99=%5.2f ms  maxFPS=%6.1f",
        (packageURL.lastPathComponent as NSString).utf8String!,
        (label as NSString).utf8String!,
        stats.meanMs, stats.p50Ms, stats.p95Ms, stats.p99Ms, stats.fps
    ))
    return stats
}

/// Benchmarks hardware VideoToolbox HEVC encoding for 2560x1440 baseline vs 2560x1728 (1440p + 288px depth strip)
/// to measure real encode latency & bitrate impact on Apple Silicon.
func benchmarkVTEncode(width: Int, height: Int, bitrateMbps: Int, frames: Int = 120) -> (encodeMs: Double, actualMbps: Double) {
    var session: VTCompressionSession?
    let status = VTCompressionSessionCreate(
        allocator: kCFAllocatorDefault,
        width: Int32(width),
        height: Int32(height),
        codecType: kCMVideoCodecType_HEVC,
        encoderSpecification: [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true
        ] as CFDictionary,
        imageBufferAttributes: nil,
        compressedDataAllocator: nil,
        outputCallback: nil,
        refcon: nil,
        compressionSessionOut: &session
    )
    precondition(status == noErr && session != nil, "VTCompressionSessionCreate failed: \(status)")
    let vt = session!

    VTSessionSetProperty(vt, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
    VTSessionSetProperty(vt, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
    VTSessionSetProperty(vt, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: bitrateMbps * 1_000_000))
    VTSessionSetProperty(vt, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: NSNumber(value: 72))
    VTCompressionSessionPrepareToEncodeFrames(vt)

    var pb: CVPixelBuffer?
    let attrs: [String: Any] = [
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        kCVPixelBufferMetalCompatibilityKey as String: true
    ]
    CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs as CFDictionary, &pb)
    let pixelBuffer = pb!

    // Fill with non-trivial synthetic gradient + motion pattern so encoder does real work.
    CVPixelBufferLockBaseAddress(pixelBuffer, [])
    if let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) {
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let yPtr = yBase.bindMemory(to: UInt8.self, capacity: yStride * height)
        for y in 0..<height {
            for x in 0..<width {
                yPtr[y * yStride + x] = UInt8((x &+ y &* 3) & 0xFF)
            }
        }
    }
    CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

    var totalBytes = 0
    var times: [Double] = []
    times.reserveCapacity(frames)

    let sem = DispatchSemaphore(value: 0)
    for i in 0..<frames {
        // Perturb a few rows per frame to avoid trivial skip-macroblock shortcuts.
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let yBase = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) {
            let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
            let yPtr = yBase.bindMemory(to: UInt8.self, capacity: yStride * height)
            let row = (i * 19) % height
            for x in stride(from: 0, to: width, by: 4) {
                yPtr[row * yStride + x] = UInt8((x &+ i &* 17) & 0xFF)
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        let pts = CMTime(value: CMTimeValue(i), timescale: 72)
        let t0 = nowMs()
        var encBytes = 0
        VTCompressionSessionEncodeFrame(vt, imageBuffer: pixelBuffer, presentationTimeStamp: pts, duration: CMTime(value: 1, timescale: 72), frameProperties: nil, infoFlagsOut: nil) { _, _, sampleBuffer in
            if let sb = sampleBuffer, let bb = CMSampleBufferGetDataBuffer(sb) {
                encBytes = CMBlockBufferGetDataLength(bb)
            }
            sem.signal()
        }
        sem.wait()
        let t1 = nowMs()
        if i >= 15 {
            times.append(t1 - t0)
            totalBytes += encBytes
        }
    }

    VTCompressionSessionInvalidate(vt)
    let stats = computeStats(times)
    let durationSec = Double(times.count) / 72.0
    let mbps = (Double(totalBytes) * 8.0 / 1_000_000.0) / durationSec
    return (stats.meanMs, mbps)
}

func main() throws {
    let args = Array(CommandLine.arguments.dropFirst())
    guard !args.isEmpty else {
        print("Usage: bench-coreml <model1.mlpackage> [model2.mlpackage ...]")
        exit(1)
    }

    print("=== Core ML ZipDepth Inference Benchmark (15 warmup + 100 timed frames) ===")
    let unitSets: [(MLComputeUnits, String)] = [
        (.all, "ANE+GPU+CPU"),
        (.cpuAndNeuralEngine, "CPU+ANE"),
        (.cpuAndGPU, "CPU+GPU")
    ]
    for path in args {
        let url = URL(fileURLWithPath: path)
        for (units, label) in unitSets {
            _ = try benchmarkModel(at: url, units: units, label: label)
        }
    }

    print("\n=== Hardware VideoToolbox HEVC 72fps Encode Comparison ===")
    let base = benchmarkVTEncode(width: 2560, height: 1440, bitrateMbps: 40)
    print(String(format: "  Baseline 2560x1440 (1440p)                 : mean=%.2f ms/frame, measured=%.1f Mbps", base.encodeMs, base.actualMbps))

    let packedBottom = benchmarkVTEncode(width: 2560, height: 1728, bitrateMbps: 45)
    print(String(format: "  Packed   2560x1728 (1440p + 288px depth)   : mean=%.2f ms/frame, measured=%.1f Mbps (+%.2f ms)", packedBottom.encodeMs, packedBottom.actualMbps, packedBottom.encodeMs - base.encodeMs))

    let sbsFull = benchmarkVTEncode(width: 3840, height: 1440, bitrateMbps: 55)
    print(String(format: "  SBS      3840x1440 (1440p + half-W depth)  : mean=%.2f ms/frame, measured=%.1f Mbps (+%.2f ms)", sbsFull.encodeMs, sbsFull.actualMbps, sbsFull.encodeMs - base.encodeMs))
}

try main()
