package com.firewalldo.portal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OpenAiErrorsTest {
    @Test void distinguishesQuotaRateAndSpendLimitsWithoutProviderBodyOrSecrets() {
        assertThat(OpenAiErrors.failure(429,"{\"error\":{\"code\":\"credit_balance_exhausted\",\"message\":\"sk-secret-do-not-display\"}}",null).getMessage()).contains("créditos","faturamento").doesNotContain("sk-secret");
        assertThat(OpenAiErrors.failure(429,"{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"project_spend_limit_exceeded\"}}",null).getMessage()).contains("projeto","limite de gastos");
        assertThat(OpenAiErrors.failure(429,"{\"error\":{\"code\":\"rate_limit_exceeded\"}}","45").getMessage()).contains("temporário","45 segundos").doesNotContain("créditos acabaram");
        assertThat(OpenAiErrors.failure(429,"{\"error\":{\"type\":\"rate_limit_error\"}}","invalid").getMessage()).contains("temporário","60 segundos");
        assertThat(OpenAiErrors.failure(429,"bad response containing sk-secret",null).getMessage()).contains("sem um código").doesNotContain("sk-secret");
        assertThat(OpenAiErrors.failure(401,"{\"error\":{\"message\":\"secret\"}}",null).getMessage()).contains("chave").doesNotContain("secret");
        assertThat(OpenAiErrors.failure(404,"{\"error\":{\"code\":\"model_not_found\"}}",null).getMessage()).contains("modelo");
    }
}
