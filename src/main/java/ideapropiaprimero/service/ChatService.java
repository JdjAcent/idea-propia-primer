package ideapropiaprimero.service;

import ideapropiaprimero.integration.ai.AiClient;
import ideapropiaprimero.integration.ai.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {

    private static final int MAX_RESPUESTA_PREVIA = 600;

    private final AiClient ai;
    private final TutorSkill skill;

    public ChatService(AiClient ai, TutorSkill skill) {
        this.ai = ai;
        this.skill = skill;
    }

    public String responder(List<ChatMessage> historial) {

        if (historial == null || historial.isEmpty()
                || historial.size() > 21
                || historial.size() % 2 == 0) {

            throw new IllegalArgumentException(
                    "Envía hasta 10 pares de mensajes previos y una pregunta nueva."
            );
        }

        for (int i = 0; i < historial.size(); i++) {
            ChatMessage message = historial.get(i);

            String expectedRole = i % 2 == 0 ? "user" : "assistant";
            int limit = expectedRole.equals("user") ? 4000 : 20000;

            if (message == null
                    || !expectedRole.equals(message.role())
                    || message.content() == null
                    || message.content().isBlank()
                    || message.content().length() > limit) {

                throw new IllegalArgumentException(
                        "Mensajes inválidos: alterna user y assistant, terminando en user. "
                        + "Máximo 4000 caracteres por pregunta y 20000 por respuesta previa."
                );
            }
        }

        ChatMessage ultimo = historial.get(historial.size() - 1);
        boolean confusion = skill.esConfusion(ultimo.content());

        String respuesta = ai.responder(armar(historial, confusion, false));
        if (confusion && skill.muestraCalculo(respuesta)) {
            respuesta = ai.responder(armar(historial, true, true));
            if (skill.muestraCalculo(respuesta)) {
                return skill.respuestaConfusion();
            }
        }
        return respuesta;
    }

    private List<ChatMessage> armar(List<ChatMessage> historial, boolean confusion, boolean estricto) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", skill.sistema()));

        for (int i = 0; i < historial.size() - 1; i++) {
            ChatMessage previo = historial.get(i);
            messages.add(previo.role().equals("assistant")
                    ? new ChatMessage("assistant", recortar(previo.content()))
                    : previo);
        }

        ChatMessage ultimo = historial.get(historial.size() - 1);
        String recordatorio = skill.recordatorio(confusion) + (estricto ? "\n" + skill.recordatorioEstricto() : "");
        messages.add(new ChatMessage("user", ultimo.content() + "\n\n" + recordatorio));
        return messages;
    }

    private static String recortar(String texto) {
        if (texto.length() <= MAX_RESPUESTA_PREVIA) return texto;
        return texto.substring(0, MAX_RESPUESTA_PREVIA).stripTrailing() + "…";
    }
}
