package com.todo.backend.controller.auth;

import com.todo.backend.controller.auth.dto.*;
import com.todo.backend.dao.UserRepository;
import com.todo.backend.entity.identity.UserRole;
import com.todo.backend.utils.auth.JwtUtils;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@CrossOrigin("*")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final JwtUtils jwtUtils;
    private final AuthenticationManager authManager;
    private final UserRepository userRepository;

    // test route to demo how to get logged in user id
    // and require roles for single route
    @GetMapping("/test")
    @PreAuthorize("hasAnyAuthority('LIBRARIAN', 'ADMIN')")
    public ResponseEntity<?> test() {
        // get user from security context
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var userDetails = (UserDetails) auth.getPrincipal();
        var userId = userDetails.getUsername();
        return ResponseEntity.ok(userId);
    }

    // login route
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto req, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return ResponseEntity.badRequest().body(bindingResult.getFieldErrors());
        }
        Authentication auth;
        try {
            auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.cccd(), req.password())
            );
        } catch (AuthenticationException e) {
            return ResponseEntity.badRequest().body("Invalid credentials");
        }

        SecurityContextHolder.getContext().setAuthentication(auth);
        var userDetails = (UserDetails) auth.getPrincipal();
        // Optionally get authorities from userDetails if not embedded in the token
        var firstAuthority = userDetails.getAuthorities().stream().findFirst().orElseThrow().getAuthority();
        var role = UserRole.valueOf(firstAuthority);

        // username is the id, because fuck java
        var refreshToken = jwtUtils.generateRefreshToken(userDetails.getUsername());
        var accessToken = jwtUtils.generateAccessToken(userDetails.getUsername(), role);

        return ResponseEntity.ok(new LoginResultDto(
                accessToken, refreshToken
        ));
    }

    // refresh jwt
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshTokenDto req) {
        String userId;
        try {
            userId = jwtUtils.validateAndExtractIdRefreshToken(req.refreshToken());
        } catch (JwtException e) {
            return ResponseEntity.badRequest().body("Invalid refresh token");
        }

        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body("User with ID " + userId + " no longer exists");
        }
        var token = jwtUtils.generateAccessToken(userId, user.getRole());
        return ResponseEntity.ok().body(new RefreshResultDto(token));
    }
}
