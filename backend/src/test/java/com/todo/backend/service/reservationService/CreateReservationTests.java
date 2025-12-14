package com.todo.backend.service.reservationService;

import com.todo.backend.dao.BookCopyRepository;
import com.todo.backend.dao.BookTitleRepository;
import com.todo.backend.dao.ReservationRepository;
import com.todo.backend.dao.UserRepository;
import com.todo.backend.dto.reservation.ResponseReservationDto;
import com.todo.backend.entity.*;
import com.todo.backend.mapper.ReservationMapper;
import com.todo.backend.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CreateReservationTests {
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private BookTitleRepository bookTitleRepository;
    @Mock
    private BookCopyRepository bookCopyRepository;
    @Mock
    private UserRepository userRepository;
    @Spy
    private ReservationMapper reservationMapper = Mappers.getMapper(ReservationMapper.class);

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void TC1_OK() {
        /// Arrange
        // user
        var user = initUser();
        user.setBalance(10);
        int depositAmount = 9;
        int remainingBalance = 1;
        // book title
        var bookTitle = initBookTitle();
        int price = depositAmount * 10; // 10% of book price as deposit
        bookTitle.setPrice(price);
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        when(reservationRepository.findActiveReservationsByBookTitleId(eq(bookTitle.getId()), any()))
                .thenReturn(List.of());
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        ResponseReservationDto res;
        try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
            res = reservationService.createReservation(user.getId(), reservationDto);
        }
        /// Assert
        // dto
        assertEquals(bookTitle.getId(), res.getBookTitleId());
        assertEquals(user.getId(), res.getUserId());
        // repository save
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertEquals(bookTitle.getId(), captor.getValue().getBookTitleId());
        assertEquals(user.getId(), captor.getValue().getUserId());
        // user balance deduction
        verify(userRepository).save(argThat(
                u -> u.getId().equals(user.getId()) && u.getBalance() == remainingBalance));
    }


    @Test
    void TC2_UserNotExist() {
        /// Arrange
        // user
        var nonExistentUserId = "non-existent-user-id";
        var user = new User();
        user.setId(nonExistentUserId);
        when(userRepository.findById(nonExistentUserId)).thenReturn(Optional.empty());
        // book title
        var bookTitle = initBookTitle();
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(reservation.getUserId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertEquals("User not found", res.getMessage());
    }

    @Test
    void TC3_BookTitleNotExist() {
        /// Arrange
        // user
        var user = initUser();
        // book title
        var bookTitle = initBookTitle(false);
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(reservation.getUserId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertEquals("Book title not found", res.getMessage());
    }

    @Test
    void TC4_UserMaxedActiveReservations() {
        /// Arrange
        // user
        var user = initUser();
        // book title
        var bookTitle = initBookTitle();
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(Collections.nCopies(6, new Reservation()));
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertEquals("User has reached the maximum number of active reservations", res.getMessage());
    }

    @Test
    void TC5_UserHasActiveReserveForTitle() {
        /// Arrange
        // user
        var user = initUser();
        // book title
        var bookTitle = initBookTitle();
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(Collections.nCopies(1, reservation));
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertEquals("User has already reserved this book", res.getMessage());
    }

    @Test
    void TC6_BookTitleDisallowBorrow() {
        /// Arrange
        // user
        var user = initUser();
        // book title
        var bookTitle = initBookTitle();
        bookTitle.setCanBorrow(false);
        bookTitle.setMaxOnlineReservations(10);
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertEquals("This book title cannot be reserved", res.getMessage());
    }

    @Test
    void TC7_BookTitleMaxedReserveSlots() {
        /// Arrange
        // user
        var user = initUser();
        // book title
        var bookTitle = initBookTitle();
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(1);
        when(reservationRepository.findActiveReservationsByBookTitleId(eq(bookTitle.getId()), any()))
                .thenReturn(List.of(new Reservation()));
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertEquals("No more online reservation slots available for this book", res.getMessage());
    }

    @Test
    void TC8_NotEnoughBalance() {
        /// Arrange
        // user
        var user = initUser();
        user.setBalance(10);
        // book title
        var bookTitle = initBookTitle();
        int depositAmount = 11;
        int price = depositAmount * 10; // 10% of book price as deposit
        bookTitle.setPrice(price);
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        when(reservationRepository.findActiveReservationsByBookTitleId(eq(bookTitle.getId()), any()))
                .thenReturn(List.of());
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        var res = assertThrows(RuntimeException.class, () -> {
            try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
                reservationService.createReservation(user.getId(), reservationDto);
            }
        });
        /// Assert
        assertTrue(res.getMessage().startsWith("User does not have enough balance to reserve. Required deposit: 11 "));
    }


    @Test
    void TC9_JustEnoughBalance() {
        /// Arrange
        // user
        var user = initUser();
        user.setBalance(10);
        int depositAmount = 10;
        int remainingBalance = 0;
        // book title
        var bookTitle = initBookTitle();
        int price = depositAmount * 10; // 10% of book price as deposit
        bookTitle.setPrice(price);
        bookTitle.setCanBorrow(true);
        bookTitle.setMaxOnlineReservations(10);
        when(reservationRepository.findActiveReservationsByBookTitleId(eq(bookTitle.getId()), any()))
                .thenReturn(List.of());
        // reservation
        var reservation = initReservation(bookTitle, user, true, true, true);
        when(reservationRepository.findActiveReservationsByUserId(eq(user.getId()), any()))
                .thenReturn(List.of());
        // dto
        var reservationDto = reservationMapper.toDto(reservation);

        /// Act
        ResponseReservationDto res;
        try (var ignored = mockStatic(TransactionSynchronizationManager.class)) {
            res = reservationService.createReservation(user.getId(), reservationDto);
        }
        /// Assert
        // dto
        assertEquals(bookTitle.getId(), res.getBookTitleId());
        assertEquals(user.getId(), res.getUserId());
        // repository save
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertEquals(bookTitle.getId(), captor.getValue().getBookTitleId());
        assertEquals(user.getId(), captor.getValue().getUserId());
        // user balance deduction
        verify(userRepository).save(argThat(
                u -> u.getId().equals(user.getId()) && u.getBalance() == remainingBalance));
    }

    User initUser() {
        var userId = "user-id";
        var user = new User();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        return user;
    }
    BookTitle initBookTitle() {
        return initBookTitle(true);
    }
    BookTitle initBookTitle(boolean exists) {
        var bookTitle = new BookTitle();
        bookTitle.setId("book-title-id");
        if (!exists) {
            when(bookTitleRepository.findById(bookTitle.getId())).thenReturn(Optional.empty());
            return bookTitle;
        }
        bookTitle.setTitle("book-title");
        var author = new BookAuthor();
        author.setAuthor(new Author());
        bookTitle.setBookAuthors(List.of(author));
        when(bookTitleRepository.findById(bookTitle.getId())).thenReturn(Optional.of(bookTitle));
        return bookTitle;
    }
    Reservation initReservation(
            BookTitle bookTitle,
            User user,
            boolean reservationExists,
            boolean reservationNotExpired,
            boolean availableBookCopyExists
    ) {
        var reservationId = "reservation-id";
        var reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(user.getId());
        reservation.setUser(user);
        if (!reservationExists) {
            when(reservationRepository.findById(reservationId))
                    .thenReturn(Optional.empty());
            return reservation;
        }

        if (reservationNotExpired)
            reservation.setExpirationDate(LocalDate.now().plusDays(1));
        else {
            reservation.setExpirationDate(LocalDate.now().minusDays(1));
        }
        if (bookTitle != null) {
            reservation.setBookTitleId(bookTitle.getId());
            reservation.setBookTitle(bookTitle);
        }
        when(reservationRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation));

        if (availableBookCopyExists) {
            var bookCopy = new BookCopy();
            bookCopy.setId("book-copy-id");
            bookCopy.setStatus(BookCopyStatus.AVAILABLE);
            when(bookCopyRepository.findFirstByBookTitleIdAndStatus(
                    reservation.getBookTitle().getId(),
                    BookCopyStatus.AVAILABLE
            )).thenReturn(bookCopy);
        }
        return reservation;
    }
}
