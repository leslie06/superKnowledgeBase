// 扫描版 PDF 文字识别（苹果 Vision 框架，仅 macOS）。后端 IngestionService 解析 PDF 时，对没有文字层的页调用它。
//
// 编译：swiftc -O scripts/ocr/vision_ocr.swift -o scripts/ocr/vision-ocr
// 用法：vision-ocr <PDF 路径> <DPI> <页码...>（页码从 1 开始）
// 输出：每识别完一页，向标准输出写一行 JSON 并立即刷新，后端据此保存结果、更新进度：
//   {"page": 12, "engine": "apple-vision", "text": "第一行\n第二行", "ms": 650}
//   某页失败时：{"page": 12, "engine": "apple-vision", "error": "原因"}
// 换用其他 OCR 引擎（如 Linux 上的 RapidOCR）时，只要提供参数和输出格式相同的命令，修改 skb.ocr.command 即可。

import AppKit
import Foundation
import PDFKit
import Vision

let engine = "apple-vision"
/// 渲染后长边的像素上限，避免超大幅面的页面占用过多内存
let maxPixels = 4000.0

func emit(_ object: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: object, options: [])
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write("\n".data(using: .utf8)!)
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(2)
}

func render(_ page: PDFPage, dpi: Double) -> CGImage? {
    let box = page.bounds(for: .mediaBox)
    let scale = min(dpi / 72.0, maxPixels / max(box.width, box.height))
    let width = Int(box.width * scale), height = Int(box.height * scale)
    guard width > 0, height > 0,
          let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
    ctx.setFillColor(CGColor(gray: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    ctx.scaleBy(x: scale, y: scale)
    page.draw(with: .mediaBox, to: ctx)
    return ctx.makeImage()
}

/// Vision 返回的是一个个文字框。按纵向位置分行：与当前行纵向重叠超过一半的框归入同一行，行内按横坐标排序。
func lines(_ observations: [VNRecognizedTextObservation]) -> [String] {
    // Vision 坐标原点在左下角，maxY 越大越靠上
    let sorted = observations.sorted { $0.boundingBox.maxY > $1.boundingBox.maxY }
    var rows: [[VNRecognizedTextObservation]] = []
    for obs in sorted {
        if let last = rows.last, let anchor = last.first {
            let a = anchor.boundingBox, b = obs.boundingBox
            let overlap = min(a.maxY, b.maxY) - max(a.minY, b.minY)
            if overlap > min(a.height, b.height) / 2 {
                rows[rows.count - 1].append(obs)
                continue
            }
        }
        rows.append([obs])
    }
    return rows.map { row in
        row.sorted { $0.boundingBox.minX < $1.boundingBox.minX }
            .compactMap { $0.topCandidates(1).first?.string }
            .joined(separator: " ")
    }.map(fixPunctuation)
}

/// Vision 会把代码、命令里的括号逗号识别成中文全角标点。以英文为主的行换回半角。
let fullWidth: [Character: Character] = ["（": "(", "）": ")", "，": ",", "；": ";", "：": ":", "“": "\"", "”": "\"",
                                         "‘": "'", "’": "'", "【": "[", "】": "]", "！": "!", "？": "?"]

func fixPunctuation(_ line: String) -> String {
    let letters = line.unicodeScalars.filter { CharacterSet.letters.contains($0) }
    let ascii = letters.filter { $0.isASCII }.count
    guard !letters.isEmpty, ascii * 2 > letters.count else { return line }
    return String(line.map { fullWidth[$0] ?? $0 })
}

let args = CommandLine.arguments
guard args.count >= 4, let dpi = Double(args[2]) else {
    fail("用法：vision-ocr <PDF 路径> <DPI> <页码...>")
}
guard let document = PDFDocument(url: URL(fileURLWithPath: args[1])) else {
    fail("无法打开 PDF：\(args[1])")
}
for number in args[3...].compactMap({ Int($0) }) {
    autoreleasepool {
        let start = Date()
        guard let page = document.page(at: number - 1), let image = render(page, dpi: dpi) else {
            emit(["page": number, "engine": engine, "error": "页码超出范围或渲染失败"])
            return
        }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["zh-Hans", "en-US"]
        request.usesLanguageCorrection = true
        do {
            try VNImageRequestHandler(cgImage: image).perform([request])
            let text = lines(request.results ?? []).joined(separator: "\n")
            emit(["page": number, "engine": engine, "text": text, "ms": Int(Date().timeIntervalSince(start) * 1000)])
        } catch {
            emit(["page": number, "engine": engine, "error": error.localizedDescription])
        }
    }
}
