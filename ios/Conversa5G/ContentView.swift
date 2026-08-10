import SwiftUI
import WebKit
import Network
import CoreTelephony

final class FiveGMonitor: ObservableObject {
    @Published var allowed = false
    @Published var status = "Verificando rede 5G…"

    private let pathMonitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.alysson.conversa5g.network")
    private let telephony = CTTelephonyNetworkInfo()
    private var pathIsCellular = false
    private var timer: Timer?

    init() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self else { return }
                self.pathIsCellular = path.status == .satisfied && path.usesInterfaceType(.cellular) && !path.usesInterfaceType(.wifi)
                self.evaluate()
            }
        }
        pathMonitor.start(queue: queue)

        timer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.evaluate()
        }
        evaluate()
    }

    deinit {
        pathMonitor.cancel()
        timer?.invalidate()
    }

    private func currentRadioTechnology() -> String? {
        let technologies = telephony.serviceCurrentRadioAccessTechnology ?? [:]
        if let dataService = telephony.dataServiceIdentifier,
           let technology = technologies[dataService] {
            return technology
        }
        return technologies.values.first
    }

    private func evaluate() {
        let radio = currentRadioTechnology()
        let is5G = radio == CTRadioAccessTechnologyNR || radio == CTRadioAccessTechnologyNRNSA
        allowed = pathIsCellular && is5G

        if allowed {
            status = "5G ativo"
        } else if !pathIsCellular {
            status = "Conecte o iPhone somente pela rede móvel 5G. Wi‑Fi, 4G e outras redes são bloqueadas."
        } else {
            status = "Rede móvel detectada, mas o iPhone não está registrado em 5G/NR."
        }
    }
}

struct ContentView: View {
    @StateObject private var monitor = FiveGMonitor()

    var body: some View {
        Group {
            if monitor.allowed {
                ChatWebView()
                    .ignoresSafeArea(.container, edges: .bottom)
            } else {
                VStack(spacing: 18) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.system(size: 52, weight: .semibold))
                    Text("Conversa 5G")
                        .font(.largeTitle.bold())
                    Text("REDE 5G OBRIGATÓRIA")
                        .font(.caption.bold())
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.green.opacity(0.15))
                        .clipShape(Capsule())
                    Text(monitor.status)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 28)
                    ProgressView()
                }
            }
        }
    }
}

struct ChatWebView: UIViewRepresentable {
    private let frontendURL = URL(string: "https://raw.githubusercontent.com/alyssonpompeu/s21-monitor/main/chat/src/main/assets/index.html")!

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        context.coordinator.loadFrontend(into: webView, from: frontendURL)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        private var didLoad = false

        func loadFrontend(into webView: WKWebView, from url: URL) {
            guard !didLoad else { return }
            didLoad = true

            let configuration = URLSessionConfiguration.ephemeral
            configuration.allowsCellularAccess = true
            configuration.waitsForConnectivity = false
            let session = URLSession(configuration: configuration)
            session.dataTask(with: url) { data, _, error in
                DispatchQueue.main.async {
                    guard error == nil,
                          let data,
                          let html = String(data: data, encoding: .utf8) else {
                        webView.loadHTMLString(Self.errorHTML("Não foi possível carregar o mensageiro pela rede 5G."), baseURL: nil)
                        return
                    }
                    webView.loadHTMLString(html, baseURL: URL(string: "https://raw.githubusercontent.com/"))
                }
            }.resume()
        }

        private static func errorHTML(_ message: String) -> String {
            """
            <!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>body{font-family:-apple-system;padding:32px;background:#f4f6f8;color:#111827}div{background:white;padding:22px;border-radius:18px}</style>
            <div><h2>Conversa 5G</h2><p>\(message)</p></div>
            """
        }
    }
}
