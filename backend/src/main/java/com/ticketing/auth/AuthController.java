package com.ticketing.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 계약 4.1 User. 본인에게만 주므로 이메일을 마스킹하지 않는다. */
    public record UserView(long id, String email, String name) {
        static UserView of(AuthService.User u) {
            return new UserView(u.id(), u.email(), u.name());
        }
    }

    /** 계약 4.1 AuthState. user는 비로그인이면 null(필드는 항상 있음). */
    public record AuthState(UserView user, String csrfToken) {
    }

    public record SignupRequest(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Size(max = 254, message = "이메일이 너무 깁니다.")
            @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8~72자로 입력해 주세요.")
            String password,

            @NotBlank(message = "이름을 입력해 주세요.")
            @Size(max = 40, message = "이름은 40자 이내로 입력해 주세요.")
            @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "이름에 사용할 수 없는 문자가 있습니다.")
            String name) {
    }

    /** 로그인은 형식 검증을 느슨하게(형식 오류로 계정 존재 여부를 추측할 단서를 주지 않게) 길이만 본다. */
    public record LoginRequest(
            @NotBlank(message = "이메일을 입력해 주세요.") @Size(max = 254, message = "이메일이 너무 깁니다.") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요.") @Size(max = 200, message = "비밀번호가 너무 깁니다.") String password) {
    }

    private final AuthService auth;
    private final SessionAuth sessions;

    public AuthController(AuthService auth, SessionAuth sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    /** 항상 200. CSRF 토큰을 위해 익명 세션이 없으면 만든다. */
    @GetMapping("/me")
    public AuthState me(HttpServletRequest request) {
        String csrf = sessions.csrfToken(request);
        UserView user = sessions.userId(request)
                .flatMap(auth::findUser)
                .map(UserView::of)
                .orElse(null);
        if (user == null) {
            sessions.forgetUser(request); // 계정이 사라졌거나 로그인 불가가 된 세션
        }
        return new AuthState(user, csrf);
    }

    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthState> signup(HttpServletRequest request, @Valid @RequestBody SignupRequest body) {
        AuthService.User user = auth.signup(body.email(), body.password(), body.name());
        String csrf = sessions.login(request, user.id()); // 가입과 동시에 로그인
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthState(UserView.of(user), csrf));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AuthState login(HttpServletRequest request, @Valid @RequestBody LoginRequest body) {
        AuthService.User user = auth.authenticate(body.email(), body.password());
        String csrf = sessions.login(request, user.id());
        return new AuthState(UserView.of(user), csrf);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(request);
        return ResponseEntity.noContent().build();
    }
}
