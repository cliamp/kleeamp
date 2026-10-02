import CliampCore
import Foundation
import os

/// Reads ICY stream metadata from its own connection: AVPlayer does not expose
/// the title, so a second request carries `Icy-MetaData: 1` and the parser
/// pulls the titles out of the interleaved blocks. Streams without ICY are
/// cancelled immediately. This costs a second stream connection, which is the
/// price of the now-playing title until DEC-01 picks a final engine.
final class IcyMetadataReader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var parser: IcyMetadataParser?
    private var onTitle: (@Sendable (String) -> Void)?
    private let logger = Logger(subsystem: "stream.cliamp.mobile", category: "icy")

    func start(url: URL, onTitle: @escaping @Sendable (String) -> Void) {
        stop()
        self.onTitle = onTitle
        var request = URLRequest(url: url)
        request.setValue("1", forHTTPHeaderField: "Icy-MetaData")
        request.setValue("cliamp-mobile/0.0.1 (+https://cliamp.stream)", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.dataTask(with: request)
        self.task = task
        task.resume()
    }

    func stop() {
        task?.cancel()
        task = nil
        session?.invalidateAndCancel()
        session = nil
        parser = nil
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse,
              let metaint = http.value(forHTTPHeaderField: "icy-metaint").flatMap(Int.init),
              metaint > 0
        else {
            completionHandler(.cancel)
            return
        }
        parser = IcyMetadataParser(metaint: metaint)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard var parser else { return }
        let titles = parser.consume(data)
        self.parser = parser
        guard let title = titles.last else { return }
        onTitle?(title)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error, (error as NSError).code != NSURLErrorCancelled {
            logger.debug("icy reader ended: \(error.localizedDescription, privacy: .public)")
        }
    }
}
