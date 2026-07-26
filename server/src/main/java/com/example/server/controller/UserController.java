package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import com.example.server.utils.JwtUtils;
import com.example.server.utils.PasswordUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/user")
// Added to make sure no cross-origin (CORS) case slips through.
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class UserController {

    @Autowired(required = false)
    private UserMapper userMapper;

    @Autowired
    private PasswordUtils passwordUtils;

    @Autowired
    private JwtUtils jwtUtils;

    // Email format validation
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // Password rule: at least 8 chars, containing an uppercase letter, a lowercase letter, and a digit.
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    // Registration endpoint
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Log to confirm the request arrived
            System.out.println("Received registration request: " + user.getEmail());

            // Verify the mapper was injected
            if (userMapper == null) {
                throw new RuntimeException("UserMapper not injected — check the @Mapper annotation!");
            }

            String email = user.getEmail() == null ? "" : user.getEmail().trim();
            String password = user.getPassword() == null ? "" : user.getPassword();

            // Server-side email validation
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                result.put("code", 400);
                result.put("msg", "Please enter a valid email address");
                return result;
            }

            // Server-side password-strength validation
            if (!PASSWORD_PATTERN.matcher(password).matches()) {
                result.put("code", 400);
                result.put("msg", "Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a digit");
                return result;
            }

            // Ensure the email is not already taken
            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("email", email);
            if (userMapper.selectCount(query) > 0) {
                result.put("code", 400);
                result.put("msg", "This email is already registered");
                return result;
            }

            user.setEmail(email);
            // Key: store a salted hash, never plaintext
            user.setPassword(passwordUtils.hash(password));

            // Default nickname
            if (user.getNickname() == null || user.getNickname().isEmpty()) {
                user.setNickname("User" + System.currentTimeMillis());
            }
            user.setRole("USER");

            userMapper.insert(user); // The key action: persist to the database

            user.setPassword(null); // Never return the password hash
            result.put("code", 200);
            result.put("msg", "Registered successfully");
            result.put("data", user);
        } catch (Exception e) {
            // If this error shows up in the console, it explains the cause.
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "Server error: " + e.getMessage());
        }
        return result;
    }

    // Login endpoint (sign in with email)
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User loginUser) {
        Map<String, Object> result = new HashMap<>();
        try {
            System.out.println("Received login request: " + loginUser.getEmail());

            // Look up by email, then verify the salted hash
            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("email", loginUser.getEmail() == null ? "" : loginUser.getEmail().trim());
            User dbUser = userMapper.selectOne(query);

            // Wrong email or wrong password returns the same generic message (don't leak which one failed).
            if (dbUser == null || !passwordUtils.matches(loginUser.getPassword(), dbUser.getPassword())) {
                result.put("code", 401);
                result.put("msg", "Incorrect email or password");
                return result;
            }

            dbUser.setPassword(null); // Never return the password hash
            result.put("code", 200);
            result.put("msg", "Signed in successfully");
            // Issue a real signed JWT
            result.put("token", jwtUtils.generate(dbUser.getId()));
            result.put("userInfo", dbUser);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "Sign-in error: " + e.getMessage());
        }
        return result;
    }
}
