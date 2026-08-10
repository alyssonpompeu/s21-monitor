using Microsoft.Web.WebView2.Core;
using System.IO;
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
            var userData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ConversaDesktop",
                "WebView2");

            Directory.CreateDirectory(userData);
            var environment = await CoreWebView2Environment.CreateAsync(null, userData);
            await Browser.EnsureCoreWebView2Async(environment);

            var assets = Path.Combine(AppContext.BaseDirectory, "Assets");
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
