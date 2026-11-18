package com.ticketing.auth;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(String email, String passwordHash, String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (email, password_hash, name) VALUES (:email, :hash, :name)")
                .param("email", email).param("hash", passwordHash).param("name", name)
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public Optional<UserRow> findByEmail(String normalizedEmail) {
        return jdbc.sql("SELECT id, email, name, password_hash, login_enabled FROM users WHERE email = :email")
                .param("email", normalizedEmail).query(JdbcUserRepository::map).optional();
    }

    @Override
    public Optional<UserRow> findById(long id) {
        return jdbc.sql("SELECT id, email, name, password_hash, login_enabled FROM users WHERE id = :id")
                .param("id", id).query(JdbcUserRepository::map).optional();
    }

    @Override
    public void upsertDemo(String email, String passwordHash, String name) {
        jdbc.sql("""
                        INSERT INTO users (email, password_hash, name, login_enabled) VALUES (:email, :hash, :name, TRUE) AS new
                        ON DUPLICATE KEY UPDATE password_hash = new.password_hash, name = new.name, login_enabled = TRUE
                        """)
                .param("email", email).param("hash", passwordHash).param("name", name).update();
    }

    private static UserRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                rs.getString("password_hash"), rs.getBoolean("login_enabled"));
    }
}
