import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Take a photo or video with the camera; hands back the file.
struct CameraPicker: UIViewControllerRepresentable {
    var onPicked: (URL, String) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        picker.videoQuality = .typeHigh
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let dir = FileManager.default.temporaryDirectory.appending(path: "picked", directoryHint: .isDirectory)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            if let movie = info[.mediaURL] as? URL {
                let dest = dir.appending(path: "\(UUID().uuidString).\(movie.pathExtension)")
                if (try? FileManager.default.copyItem(at: movie, to: dest)) != nil {
                    parent.onPicked(dest, "Video.\(movie.pathExtension)")
                }
            } else if let image = info[.originalImage] as? UIImage, let data = image.heicData() ?? image.jpegData(compressionQuality: 0.92) {
                let ext = image.heicData() != nil ? "heic" : "jpg"
                let dest = dir.appending(path: "\(UUID().uuidString).\(ext)")
                if (try? data.write(to: dest)) != nil {
                    parent.onPicked(dest, "Photo.\(ext)")
                }
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
