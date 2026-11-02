package ideapropiaprimero.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class TutorSkill {

    public enum Situacion {
        CONCEPTO("concepto"),
        PROBLEMA_SIN_INTENTO("problema-sin-intento"),
        INTENTO_INCORRECTO("intento-incorrecto"),
        INTENTO_PARCIAL("intento-parcial"),
        SOLUCION_PROPIA("solucion-propia"),
        PRODUCCION("produccion"),
        CONFUSION("confusion");

        private final String archivo;

        Situacion(String archivo) {
            this.archivo = archivo;
        }

        String archivo() {
            return archivo;
        }
    }

    private static final int MAX_LARGO_CONFUSION = 40;

    private static final Pattern CONFUSION = Pattern.compile(
            "\\b(no se|no entiendo|no comprendo|no le entiendo|sigo sin entender|ni idea|hazlo tu|hazlo|dimelo"
            + "|dame la respuesta|dime la respuesta|dame el resultado|solo la respuesta)\\b");

    private static final Pattern CALCULO = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*[-+*/x×÷]\\s*\\d+(?:[.,]\\d+)?\\s*=\\s*-?\\d");

    private final String base;
    private final String estilo;
    private final String recordatorio;
    private final String recordatorioConfusion;
    private final String recordatorioEstricto;
    private final String respuestaConfusion;
    private final Map<Situacion, String> fragmentos = new EnumMap<>(Situacion.class);

    public TutorSkill() {
        this.base = leer("base");
        this.estilo = leer("estilo");
        this.recordatorio = leer("recordatorio");
        this.recordatorioConfusion = leer("recordatorio-confusion");
        this.recordatorioEstricto = leer("recordatorio-estricto");
        this.respuestaConfusion = leer("respuesta-confusion");
        for (Situacion s : Situacion.values()) {
            fragmentos.put(s, leer(s.archivo()));
        }
    }

    public String sistema() {
        List<String> partes = new ArrayList<>();
        partes.add(base);
        partes.addAll(fragmentos.values());
        partes.add(estilo);
        return String.join("\n\n", partes);
    }

    public String sistema(Situacion situacion) {
        return String.join("\n\n", base, fragmentos.get(situacion), estilo);
    }

    public boolean esConfusion(String mensaje) {
        if (mensaje == null) return false;
        String n = Normalizer.normalize(mensaje, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .strip();
        if (n.length() > MAX_LARGO_CONFUSION || n.contains("?") || n.matches(".*\\d.*")) return false;
        return CONFUSION.matcher(n).find();
    }

    public boolean muestraCalculo(String respuesta) {
        return respuesta != null && CALCULO.matcher(respuesta).find();
    }

    public String recordatorio(boolean confusion) {
        return confusion ? recordatorioConfusion : recordatorio;
    }

    public String recordatorioEstricto() {
        return recordatorioEstricto;
    }

    public String respuestaConfusion() {
        return respuestaConfusion;
    }

    private static String leer(String nombre) {
        try {
            return new ClassPathResource("skills/tutor/" + nombre + ".md")
                    .getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el skill del tutor: " + nombre, e);
        }
    }
}
