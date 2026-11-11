import SwiftUI
import PDFKit
import CryptoKit

/// Lightweight iOS Companion Module (SwiftUI + Native PDFKit)
/// Shares the exact same stateless backend API, SHA-256 on-device cache, and zero-retention flow as Android.
public final class OfficeDocumentCacheManager {
    public static let shared = OfficeDocumentCacheManager()
    private let cacheDir: URL

    private init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        cacheDir = base.appendingPathComponent("DocPrismPDFCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    public func sha256Hex(for data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    public func cachedPDFURL(forHash hash: String) -> URL? {
        let target = cacheDir.appendingPathComponent("\(hash).pdf")
        return FileManager.default.fileExists(atPath: target.path) ? target : nil
    }

    public func storePDF(_ pdfData: Data, forHash hash: String) throws -> URL {
        let target = cacheDir.appendingPathComponent("\(hash).pdf")
        try pdfData.write(to: target, options: .atomic)
        return target
    }
}

public struct NativePDFKitRepresentable: UIViewRepresentable {
    public let pdfURL: URL

    public func makeUIView(context: Context) -> PDFView {
        let pdfView = PDFView()
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.document = PDFDocument(url: pdfURL)
        return pdfView
    }

    public func updateUIView(_ uiView: PDFView, context: Context) {
        if uiView.document?.documentURL != pdfURL {
            uiView.document = PDFDocument(url: pdfURL)
        }
    }
}
