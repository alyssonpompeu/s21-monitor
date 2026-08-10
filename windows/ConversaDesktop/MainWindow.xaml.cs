using Microsoft.Web.WebView2.Core;
using System.IO;
using System.Reflection;
using System.Windows;

namespace ConversaDesktop;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            var root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ConversaDesktop");
            var userData = Path.Combine(root, "WebView2");
            var assets = Path.Combine(root, "Assets");
            Directory.CreateDirectory(userData);
            Directory.CreateDirectory(assets);

            var htmlPath = Path.Combine(assets, "index.html");
            using (var stream = Assembly.GetExecutingAssembly()
                       .GetManifestResourceStream("ConversaDesktop.Assets.index.html")
                   ?? throw new InvalidOperationException("Interface interna não encontrada."))
            using (var output = File.Create(htmlPath))
            {
                stream.CopyTo(output);
            }

            var environment = await CoreWebView2Environment.CreateAsync(null, userData);
            await Browser.EnsureCoreWebView2Async(environment);
            Browser.CoreWebView2.SetVirtualHostNameToFolderMapping(
                "conversa.local",
                assets,
                CoreWebView2HostResourceAccessKind.Allow);

            Browser.CoreWebView2.Settings.AreDevToolsEnabled = false;
            Browser.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
            Browser.CoreWebView2.Settings.IsStatusBarEnabled = false;
            Browser.CoreWebView2.Settings.IsZoomControlEnabled = true;
            Browser.Source = new Uri("https://conversa.local/index.html");
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "Não foi possível iniciar o cliente.\n\n" + ex.Message +
                "\n\nVerifique se o Microsoft Edge WebView2 Runtime está instalado.",
                "Conversa Desktop",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
    }
}
