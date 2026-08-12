package com.example.server.controller;

import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import com.example.server.utils.JwtUtils;
import com.example.server.utils.PasswordUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration and sign-in. The properties worth pinning down here are the ones
 * that would be security bugs if they regressed: passwords must never be stored
 * or returned in the clear, validation must happen server-side (the SPA's checks
 * are not a control), and a failed sign-in must not reveal whether the email
 * exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserControllerTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordUtils passwordUtils;
    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private UserController userController;

    private User credentials(String email, String password) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(password);
        return u;
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("A valid signup stores a hash, never the plaintext, and returns no password")
        void validSignupStoresHashOnly() {
            when(userMapper.selectCount(any())).thenReturn(0L);
            when(passwordUtils.hash("Passw0rdX")).thenReturn("pbkdf2$120000$salt$hash");

            Map<String, Object> result = userController.register(credentials("a@b.com", "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(200);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(saved.capture());
            // The row must carry the hash, not what the user typed
            assertThat(saved.getValue().getPassword()).isNull(); // nulled before returning
            verify(passwordUtils).hash("Passw0rdX");
            assertThat(saved.getValue().getRole()).isEqualTo("USER");
            // A default nickname is filled in so the UI never shows a blank user
            assertThat(saved.getValue().getNickname()).isNotBlank();

            // And the response body must not leak any credential material
            User returned = (User) result.get("data");
            assertThat(returned.getPassword()).isNull();
        }

        @Test
        @DisplayName("The email is trimmed before being stored")
        void emailIsTrimmed() {
            when(userMapper.selectCount(any())).thenReturn(0L);
            when(passwordUtils.hash(anyString())).thenReturn("hashed");

            userController.register(credentials("  spaced@b.com  ", "Passw0rdX"));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo("spaced@b.com");
        }

        @ParameterizedTest
        @ValueSource(strings = {"notanemail", "no@domain", "@nolocal.com", "two@@at.com", "spa ce@b.com", ""})
        @DisplayName("Malformed emails are rejected server-side")
        void malformedEmailsRejected(String email) {
            Map<String, Object> result = userController.register(credentials(email, "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(400);
            // Nothing may be written on a rejected signup
            verify(userMapper, never()).insert(any(User.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "short1A",        // under 8 characters
                "alllowercase1",  // no uppercase
                "ALLUPPERCASE1",  // no lowercase
                "NoDigitsHere",   // no digit
        })
        @DisplayName("Weak passwords are rejected server-side, not just in the SPA")
        void weakPasswordsRejected(String password) {
            Map<String, Object> result = userController.register(credentials("a@b.com", password));

            assertThat(result.get("code")).isEqualTo(400);
            verify(userMapper, never()).insert(any(User.class));
            // Never even hash a password we are going to refuse
            verify(passwordUtils, never()).hash(anyString());
        }

        @Test
        @DisplayName("A null password is rejected rather than throwing")
        void nullPasswordRejected() {
            User u = new User();
            u.setEmail("a@b.com");

            Map<String, Object> result = userController.register(u);

            assertThat(result.get("code")).isEqualTo(400);
        }

        @Test
        @DisplayName("A duplicate email is refused")
        void duplicateEmailRefused() {
            when(userMapper.selectCount(any())).thenReturn(1L);

            Map<String, Object> result = userController.register(credentials("taken@b.com", "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(400);
            assertThat((String) result.get("msg")).contains("already registered");
            verify(userMapper, never()).insert(any(User.class));
        }

        @Test
        @DisplayName("A database failure surfaces as 500, not an unhandled exception")
        void databaseFailureBecomes500() {
            when(userMapper.selectCount(any())).thenThrow(new RuntimeException("connection reset"));

            Map<String, Object> result = userController.register(credentials("a@b.com", "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Sign-in")
    class SignIn {

        private User storedUser() {
            User u = new User();
            u.setId(7L);
            u.setEmail("a@b.com");
            u.setPassword("pbkdf2$120000$salt$hash");
            u.setRole("USER");
            return u;
        }

        @Test
        @DisplayName("Correct credentials return a signed token and no password")
        void correctCredentialsReturnToken() {
            when(userMapper.selectOne(any())).thenReturn(storedUser());
            when(passwordUtils.matches("Passw0rdX", "pbkdf2$120000$salt$hash")).thenReturn(true);
            when(jwtUtils.generate(7L)).thenReturn("issued.jwt.token");

            Map<String, Object> result = userController.login(credentials("a@b.com", "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(200);
            assertThat(result.get("token")).isEqualTo("issued.jwt.token");
            // The token is derived from the row's id, never from anything the client sent
            verify(jwtUtils).generate(7L);
            assertThat(((User) result.get("userInfo")).getPassword()).isNull();
        }

        @Test
        @DisplayName("A wrong password is refused with 401 and no token")
        void wrongPasswordRefused() {
            when(userMapper.selectOne(any())).thenReturn(storedUser());
            when(passwordUtils.matches(anyString(), anyString())).thenReturn(false);

            Map<String, Object> result = userController.login(credentials("a@b.com", "wrong"));

            assertThat(result.get("code")).isEqualTo(401);
            assertThat(result).doesNotContainKey("token");
        }

        @Test
        @DisplayName("An unknown email and a wrong password are indistinguishable")
        void unknownEmailAndWrongPasswordLookIdentical() {
            // Unknown email
            when(userMapper.selectOne(any())).thenReturn(null);
            Map<String, Object> unknown = userController.login(credentials("nobody@b.com", "Passw0rdX"));

            // Known email, wrong password
            when(userMapper.selectOne(any())).thenReturn(storedUser());
            when(passwordUtils.matches(anyString(), anyString())).thenReturn(false);
            Map<String, Object> wrongPassword = userController.login(credentials("a@b.com", "nope"));

            // Identical response, so the endpoint cannot be used to enumerate accounts
            assertThat(unknown.get("code")).isEqualTo(wrongPassword.get("code"));
            assertThat(unknown.get("msg")).isEqualTo(wrongPassword.get("msg"));
        }

        @Test
        @DisplayName("A null email does not throw")
        void nullEmailDoesNotThrow() {
            when(userMapper.selectOne(any())).thenReturn(null);

            Map<String, Object> result = userController.login(credentials(null, "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(401);
        }

        @Test
        @DisplayName("A database failure surfaces as 500")
        void databaseFailureBecomes500() {
            when(userMapper.selectOne(any())).thenThrow(new RuntimeException("connection reset"));

            Map<String, Object> result = userController.login(credentials("a@b.com", "Passw0rdX"));

            assertThat(result.get("code")).isEqualTo(500);
        }
    }
}
