package ideapropiaprimero.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ideapropiaprimero.integration.ai.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TutorSkillTest {

    private final TutorSkill skill = new TutorSkill();

    @Test
    void elSistemaCompletoEsMuchoMasCortoQueElPromptAnterior() {
        int palabras = skill.sistema().split("\\s+").length;
        assertTrue(palabras < 900, "palabras: " + palabras);
    }

    @Test
    void cadaSituacionIncluyeBaseYSoloSuFragmento() {
        String problema = skill.sistema(TutorSkill.Situacion.PROBLEMA_SIN_INTENTO);
        assertTrue(problema.contains("PROBLEMA CONCRETO SIN INTENTO"));
        assertFalse(problema.contains("PRODUCCIÓN PARA ENTREGAR"));
        assertTrue(problema.contains("Reglas que no cambian"));
    }

    @Test
    void elSistemaCompletoIncluyeTodasLasSituaciones() {
        String todo = skill.sistema();
        for (String marca : List.of("INFORMACIÓN O CONCEPTO", "PROBLEMA CONCRETO SIN INTENTO", "INTENTO INCORRECTO",
                "INTENTO CORRECTO PERO INCOMPLETO", "SOLUCIÓN PROPIA TERMINADA", "PRODUCCIÓN PARA ENTREGAR", "CONFUSIÓN SIN APORTE")) {
            assertTrue(todo.contains(marca), marca);
        }
    }

    @Test
    void chatServiceRecortaRespuestasPreviasYAgregaElRecordatorioAlFinal() {
        List<List<ChatMessage>> enviados = new ArrayList<>();
        ChatService service = new ChatService(messages -> {
            enviados.add(messages);
            return "ok";
        }, skill);

        String larga = "x".repeat(5000);
        service.responder(List.of(
                new ChatMessage("user", "hola"),
                new ChatMessage("assistant", larga),
                new ChatMessage("user", "resuelve 2x = 4")));

        List<ChatMessage> messages = enviados.get(0);
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(2).content().length() <= 601);
        ChatMessage ultimo = messages.get(messages.size() - 1);
        assertEquals("user", ultimo.role());
        assertTrue(ultimo.content().startsWith("resuelve 2x = 4"));
        assertTrue(ultimo.content().contains("Recordatorio del tutor"));
    }

    @Test
    void detectaMensajesDeConfusionCortos() {
        for (String m : List.of("no sé", "No entiendo", "hazlo tú", "no se!", "dame la respuesta", "ni idea")) {
            assertTrue(skill.esConfusion(m), m);
        }
    }

    @Test
    void noTrataComoConfusionUnIntentoOUnaPregunta() {
        for (String m : List.of("creo que debo dividir 45 entre 3, pero no sé cuánto da", "no sé, ¿sumo 4?",
                "resuelve 8x - 4 = 20", "Explícame qué es una ecuación lineal")) {
            assertFalse(skill.esConfusion(m), m);
        }
    }

    @Test
    void detectaCalculosConResultadoPeroNoEcuacionesRepetidas() {
        assertTrue(skill.muestraCalculo("Si 20 + 4 = 24, ¿qué operación sigue?"));
        assertTrue(skill.muestraCalculo("45 / 3 = 15"));
        assertFalse(skill.muestraCalculo("En 8x - 4 = 20, ¿qué operación aplicarías?"));
        assertFalse(skill.muestraCalculo("¿Qué obtienes al sumar 4 a ambos lados?"));
    }

    @Test
    void ante_confusionReintentaSiFiltraUnCalculoYLuegoUsaLaRespuestaSegura() {
        List<Integer> llamadas = new ArrayList<>();
        ChatService service = new ChatService(messages -> {
            llamadas.add(1);
            return "Si 20 + 4 = 24, ¿qué sigue?";
        }, skill);

        String respuesta = service.responder(List.of(new ChatMessage("user", "no sé")));

        assertEquals(2, llamadas.size());
        assertEquals(skill.respuestaConfusion(), respuesta);
    }

    @Test
    void ante_confusionAceptaLaRespuestaSiElReintentoSaleLimpio() {
        List<String> respuestas = new ArrayList<>(List.of("Si 20 + 4 = 24, ¿qué sigue?", "¿Qué operación elimina el -4?"));
        ChatService service = new ChatService(messages -> respuestas.remove(0), skill);

        String respuesta = service.responder(List.of(new ChatMessage("user", "hazlo tú")));

        assertEquals("¿Qué operación elimina el -4?", respuesta);
    }

    @Test
    void sinConfusionNoReintenta() {
        List<Integer> llamadas = new ArrayList<>();
        ChatService service = new ChatService(messages -> {
            llamadas.add(1);
            return "45 / 3 = 15";
        }, skill);

        service.responder(List.of(new ChatMessage("user", "Comprueba mi respuesta: 45 / 3 = 15")));

        assertEquals(1, llamadas.size());
    }
}
