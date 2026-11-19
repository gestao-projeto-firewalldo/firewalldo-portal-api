package com.firewalldo.portal;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Only recognized provider codes are used. Never echo response bodies, keys or prompts. */
final class OpenAiErrors {
    private static final ObjectMapper JSON=new ObjectMapper();
    static ApiError failure(int status,String body,String retryAfter) {
        String code="",type="";
        try {var error=JSON.readTree(body).path("error");code=error.path("code").asText("");type=error.path("type").asText("");}catch(Exception ignored){}
        String message=switch(code) {
            case "credit_balance_exhausted" -> "Os créditos da API da OpenAI acabaram. Confira o saldo no faturamento da API (platform.openai.com), na organização da chave configurada no Railway. O limite de uso do ChatGPT não é o saldo da API.";
            case "billing_not_active" -> "O faturamento da API da OpenAI não está ativo para a organização desta chave. Confira o faturamento na plataforma da API.";
            case "project_spend_limit_exceeded" -> "O projeto da API atingiu o limite de gastos configurado. Confira os limites desse projeto na plataforma da OpenAI.";
            case "organization_spend_limit_exceeded" -> "A organização da API atingiu o limite de gastos configurado. Confira os limites da organização na plataforma da OpenAI.";
            case "organization_usage_limit_exceeded" -> "A organização atingiu a cota de uso autorizada pela OpenAI. Confira os limites da API; isso não é o limite de uso do ChatGPT.";
            case "insufficient_quota","usage_limit_exceeded" -> "A API da OpenAI informou cota ou créditos indisponíveis. Confira saldo, faturamento e limites da organização/projeto da chave no Railway. A assinatura Plus não comprova saldo disponível na API.";
            case "rate_limit_exceeded","slow_down" -> temporary(retryAfter);
            case "model_not_found" -> "O modelo configurado não está disponível para este projeto da OpenAI. Confira OPENAI_MODEL e o acesso ao modelo na API.";
            default -> {
                if(type.equals("insufficient_quota"))yield "A API da OpenAI informou cota indisponível. Confira créditos e limites da organização/projeto na plataforma da API.";
                if(status==429 && type.equals("rate_limit_error"))yield temporary(retryAfter);
                yield switch(status) {
                    case 401 -> "A OpenAI recusou a chave. Confira OPENAI_API_KEY no serviço api do Railway.";
                    case 403 -> "A OpenAI recusou a permissão desta chave, projeto ou modelo. Confira o acesso na plataforma da API.";
                    case 404 -> "O modelo ou recurso da OpenAI não foi encontrado. Confira OPENAI_MODEL e o acesso do projeto.";
                    case 429 -> "A OpenAI retornou HTTP 429 sem um código de causa reconhecido. Pode ser limite temporário ou cota/saldo. Confira Usage, Limits e Billing da API; o painel de uso do ChatGPT não informa esse saldo.";
                    case 400 -> "A OpenAI recusou a configuração ou o formato da pesquisa (HTTP 400). Confira o modelo e sua compatibilidade com busca web e resposta estruturada.";
                    default -> "A OpenAI não concluiu a pesquisa (HTTP "+status+"). Tente novamente mais tarde.";
                };
            }
        };
        return new ApiError(400,"OPENAI_ERROR",message);
    }
    private static String temporary(String retryAfter) {
        int seconds=60;
        try {seconds=Math.max(1,Math.min(3600,Integer.parseInt(retryAfter)));}catch(Exception ignored){}
        return "Limite temporário de requisições da API da OpenAI. Aguarde "+seconds+" segundos antes de tentar novamente e revise a frequência da coleta. Esse erro não confirma falta de créditos.";
    }
}
