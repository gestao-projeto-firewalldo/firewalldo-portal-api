package com.firewalldo.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.stereotype.Component;

@Component
class RadarImages {
    record Image(byte[] bytes,String type,String credit,String sourceUrl,boolean illustrative) {}
    private final RadarNetwork network;
    private final ObjectMapper json;
    RadarImages(RadarNetwork network,ObjectMapper json) {this.network=network;this.json=json;}

    Optional<Image> find(String articleUrl,String query,String sourceName) {
        try {
            var page=network.get(articleUrl,1_000_000);
            if(page.type().toLowerCase(Locale.ROOT).contains("html")) {
                var meta=metadata(page.text());
                String image=meta.getOrDefault("og:image",meta.getOrDefault("twitter:image",""));
                if(!image.isBlank()) return Optional.of(download(URI.create(page.url()).resolve(image).toString(),sourceName+" · imagem da matéria original",articleUrl,false));
            }
        } catch(RuntimeException ignored) { /* Article may forbid automated access; try licensed illustration. */ }
        if(query==null || query.isBlank())return Optional.empty();
        try {
            String search=URLEncoder.encode(query.substring(0,Math.min(query.length(),100)),java.nio.charset.StandardCharsets.UTF_8);
            var response=network.get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=4&gsrsearch="+search+"&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1000",750_000);
            var pages=json.readTree(response.bytes()).path("query").path("pages");
            for(var page:pages) {
                var info=page.path("imageinfo").path(0); var meta=info.path("extmetadata");
                String license=plain(meta.path("LicenseShortName").path("value").asText());
                if(!(license.startsWith("CC BY") || license.equals("CC0") || license.toLowerCase(Locale.ROOT).contains("public domain")))continue;
                String artist=plain(meta.path("Artist").path("value").asText());
                String credit=(artist.isBlank()?"Wikimedia Commons":artist)+" · "+license+" · imagem ilustrativa";
                try { return Optional.of(download(info.path("thumburl").asText(info.path("url").asText()),trim(credit,500),info.path("descriptionurl").asText(),true)); }
                catch(RuntimeException ignored) { }
            }
        } catch(Exception ignored) { }
        return Optional.empty();
    }
    Image download(String url,String credit,String sourceUrl,boolean illustrative) {
        var image=network.get(url,2_097_152);
        byte[] b=image.bytes(); String type;
        if(b.length>=8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71)type="image/png";
        else if(b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255)type="image/jpeg";
        else if(b.length>=12 && new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))type="image/webp";
        else if(b.length>=6 && new String(b,0,6,java.nio.charset.StandardCharsets.US_ASCII).matches("GIF8[79]a"))type="image/gif";
        else throw ApiError.invalid("A imagem deve ser PNG, JPEG, WebP ou GIF de até 2 MB.");
        return new Image(b,type,trim(credit,500),PublicRadarNetwork.publicUrl(sourceUrl),illustrative);
    }
    static Map<String,String> metadata(String html) {
        var values=new HashMap<String,String>();
        try { new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback() {
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet a,int pos) {
                if(tag!=HTML.Tag.META)return;
                String key="",content=""; var names=a.getAttributeNames();
                while(names.hasMoreElements()) {Object n=names.nextElement();String name=n.toString();String value=String.valueOf(a.getAttribute(n));if(name.equalsIgnoreCase("property")||name.equalsIgnoreCase("name"))key=value.toLowerCase(Locale.ROOT);if(name.equalsIgnoreCase("content"))content=value;}
                values.putIfAbsent(key,content);
            }
        },true); }catch(IOException ignored) { }
        return values;
    }
    static String plain(String html) { return html.replaceAll("<[^>]*>"," ").replace("&amp;","&").replace("&quot;","\"").replaceAll("\\s+"," ").strip(); }
    static String trim(String text,int max) {return text.substring(0,Math.min(text.length(),max));}
}
