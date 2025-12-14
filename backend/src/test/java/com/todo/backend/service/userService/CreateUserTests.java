package com.todo.backend.service.userService;

import com.todo.backend.dao.UserRepository;
import com.todo.backend.dto.user.CreateUserDto;
import com.todo.backend.entity.User;
import com.todo.backend.entity.identity.UserRole;
import com.todo.backend.mapper.UserMapper;
import com.todo.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CreateUserTests {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Spy
    private UserMapper userMapper = Mappers.getMapper(UserMapper.class);

    @InjectMocks
    private UserService userService;

    @Test
    void TC1_OK() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("test@example.com")
                .cccd("123456789012")
                .balance(10000)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(false);
        when(userRepository.existsByCccd(createDto.getCccd()))
                .thenReturn(false);
        when(passwordEncoder.encode(createDto.getPassword()))
                .thenReturn("hashed_password");
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        /// Act
        var res = userService.createUser(createDto);

        /// Assert
        // dto
        assertEquals(createDto.getEmail(), res.getEmail());
        assertEquals(createDto.getCccd(), res.getCccd());
        // repository save
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(createDto.getEmail(), userCaptor.getValue().getEmail());
        assertEquals("hashed_password", userCaptor.getValue().getPassword());
    }

    @Test
    void TC2_EmailAlreadyExists() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("existing@example.com")
                .cccd("123456789012")
                .balance(10000)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(true);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                userService.createUser(createDto));

        /// Assert
        assertEquals("Email already exists", exception.getMessage());
    }

    @Test
    void TC3_CccdAlreadyExists() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("test@example.com")
                .cccd("999999999999")
                .balance(10000)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(false);
        when(userRepository.existsByCccd(createDto.getCccd()))
                .thenReturn(true);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                userService.createUser(createDto));

        /// Assert
        assertEquals("CCCD already exists", exception.getMessage());
    }

    @Test
    void TC4_NegativeBalance() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("test@example.com")
                .cccd("123456789012")
                .balance(-5000)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(false);
        when(userRepository.existsByCccd(createDto.getCccd()))
                .thenReturn(false);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                userService.createUser(createDto));

        /// Assert
        assertEquals("Balance cannot be negative", exception.getMessage());
    }

    @Test
    void TC5_EmailExists_NegativeBalance() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("existing@example.com")
                .cccd("123456789012")
                .balance(-5000)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(true);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                userService.createUser(createDto));

        /// Assert
        // Email check comes first before balance validation
        assertEquals("Email already exists", exception.getMessage());
    }

    @Test
    void TC6_CccdExists_NegativeBalance() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("test@example.com")
                .cccd("999999999999")
                .balance(-5000)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(false);
        when(userRepository.existsByCccd(createDto.getCccd()))
                .thenReturn(true);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                userService.createUser(createDto));

        /// Assert
        // CCCD check comes before balance validation
        assertEquals("CCCD already exists", exception.getMessage());
    }

    @Test
    void TC7_ZeroBalance() {
        /// Arrange
        var createDto = CreateUserDto.builder()
                .email("test@example.com")
                .cccd("123456789012")
                .balance(0)
                .password("password123")
                .name("Test User")
                .phone("1234567890")
                .role(UserRole.USER)
                .build();

        when(userRepository.existsByEmail(createDto.getEmail()))
                .thenReturn(false);
        when(userRepository.existsByCccd(createDto.getCccd()))
                .thenReturn(false);
        when(passwordEncoder.encode(createDto.getPassword()))
                .thenReturn("hashed_password");
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        /// Act
        var res = userService.createUser(createDto);

        /// Assert
        // dto
        assertEquals(createDto.getEmail(), res.getEmail());
        assertEquals(0, res.getBalance());
        // repository save
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(0, userCaptor.getValue().getBalance());
    }
}
