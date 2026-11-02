package ideapropiaprimero.repository;

import ideapropiaprimero.model.Chat;
import ideapropiaprimero.model.StoredMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Historial de conversaciones en la misma base SQLite que UserRepository.
 *
 * Las conversaciones no se borran a propósito: el auxiliar debe poder revisar
 * el historial de un estudiante. Los mensajes se guardan siempre de a pares
 * (user + assistant) en una sola transacción, así el historial siempre alterna
 * como exige ChatService.
 */
@Repository
public class ChatRepository {

    /** 3 pares user/assistant de contexto: menos tokens y la IA no imita sus propias respuestas largas. */
    public static final int MAX_CONTEXT_MESSAGES = 6;

    private final String url;

    public ChatRepository(@Value("${app.database.path:data/idea-propia-primero.db}") String path) {
        try {
            Path file = Path.of(path).toAbsolutePath();
            Files.createDirectories(file.getParent());
            url = "jdbc:sqlite:" + file;
            try (Connection c = connect(); Statement st = c.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS chats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (user_id) REFERENCES users(id)
                    )
                    """);
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id INTEGER NOT NULL,
                        role TEXT NOT NULL CHECK(role IN ('user','assistant')),
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
                    )
                    """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chats_user ON chats(user_id, updated_at)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, id)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar el historial de chats.", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /** Conversaciones del usuario, la más reciente primero. */
    public List<Chat> listChats(long userId) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT id, title, updated_at FROM chats WHERE user_id = ? ORDER BY updated_at DESC, id DESC")) {
            s.setLong(1, userId);
            try (ResultSet r = s.executeQuery()) {
                List<Chat> result = new ArrayList<>();
                while (r.next()) {
                    result.add(new Chat(r.getLong("id"), r.getString("title"), r.getString("updated_at")));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar el historial.", e);
        }
    }

    /** Devuelve la conversación solo si pertenece al usuario indicado. */
    public Optional<Chat> findChat(long chatId, long userId) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT id, title, updated_at FROM chats WHERE id = ? AND user_id = ?")) {
            s.setLong(1, chatId);
            s.setLong(2, userId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Chat(r.getLong("id"), r.getString("title"), r.getString("updated_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar la conversación.", e);
        }
    }

    /** Todos los mensajes de una conversación, del más antiguo al más reciente. */
    public List<StoredMessage> messages(long chatId) {
        return query("SELECT role, content, created_at FROM messages WHERE chat_id = ? ORDER BY id", chatId, null);
    }

    /** Los últimos {@code limit} mensajes, en orden cronológico. */
    public List<StoredMessage> recentMessages(long chatId, int limit) {
        return query("""
                SELECT role, content, created_at FROM (
                    SELECT id, role, content, created_at FROM messages WHERE chat_id = ? ORDER BY id DESC LIMIT ?
                ) ORDER BY id
                """, chatId, limit);
    }

    private List<StoredMessage> query(String sql, long chatId, Integer limit) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, chatId);
            if (limit != null) s.setInt(2, limit);
            try (ResultSet r = s.executeQuery()) {
                List<StoredMessage> result = new ArrayList<>();
                while (r.next()) {
                    result.add(new StoredMessage(r.getString("role"), r.getString("content"), r.getString("created_at")));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los mensajes.", e);
        }
    }

    /**
     * Guarda el mensaje del usuario y la respuesta del agente en una sola transacción.
     * Si {@code chatId} es null crea la conversación con {@code title}; si no, comprueba
     * que pertenezca a {@code userId} y actualiza su fecha. Devuelve el id de la conversación.
     */
    public long saveExchange(long userId, Long chatId, String title, String userText, String assistantText) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                long id;
                if (chatId == null) {
                    try (PreparedStatement s = c.prepareStatement(
                            "INSERT INTO chats(user_id, title) VALUES(?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        s.setLong(1, userId);
                        s.setString(2, title);
                        s.executeUpdate();
                        try (ResultSet keys = s.getGeneratedKeys()) {
                            keys.next();
                            id = keys.getLong(1);
                        }
                    }
                } else {
                    id = chatId;
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE chats SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")) {
                        s.setLong(1, id);
                        s.setLong(2, userId);
                        if (s.executeUpdate() == 0) {
                            throw new IllegalArgumentException("La conversación no existe.");
                        }
                    }
                }
                insertMessage(c, id, "user", userText);
                insertMessage(c, id, "assistant", assistantText);
                c.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la conversación.", e);
        }
    }

    private static void insertMessage(Connection c, long chatId, String role, String content) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO messages(chat_id, role, content) VALUES(?, ?, ?)")) {
            s.setLong(1, chatId);
            s.setString(2, role);
            s.setString(3, content);
            s.executeUpdate();
        }
    }
}
