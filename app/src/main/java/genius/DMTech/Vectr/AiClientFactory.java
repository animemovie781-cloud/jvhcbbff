package genius.DMTech.Vectr;

public class AiClientFactory {
    public static AiClient create(String provider, String apiBaseUrl) {
        boolean deepseekVendor = provider == null || !"openai_compat".equals(provider);
        return new DeepSeekClient(apiBaseUrl, deepseekVendor);
    }
}
