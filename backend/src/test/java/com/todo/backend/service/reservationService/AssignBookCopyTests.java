package com.todo.backend.service.reservationService;

import com.todo.backend.dao.BookCopyRepository;
import com.todo.backend.dao.BookTitleRepository;
import com.todo.backend.dao.ReservationRepository;
import com.todo.backend.dao.UserRepository;
import com.todo.backend.dto.bookcopy.UpdateBookCopyDto;
import com.todo.backend.entity.*;
import com.todo.backend.entity.identity.UserRole;
import com.todo.backend.mapper.ReservationMapper;
import com.todo.backend.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AssignBookCopyTests {
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private BookCopyRepository bookCopyRepository;
    @Spy
    private ReservationMapper reservationMapper = Mappers.getMapper(ReservationMapper.class);

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void TC1_OK() {
        /// Arrange
        var reservation = initReservation(true, true, true);

        /// Act
        var res = reservationService.assignBookCopyToReservation(reservation.getId());

        /// Assert
        // dto
        assertEquals(reservation.getBookCopyId(), res.getBookCopyId());
        // repository save
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertEquals(reservation.getBookCopyId(), captor.getValue().getBookCopyId());
    }

    @Test
    void TC2_NoAvailableBookCopy() {
        /// Arrange
        var reservation = initReservation(true, true, false);

        /// Act
        var res = assertThrows(RuntimeException.class, () ->
                reservationService.assignBookCopyToReservation(reservation.getId()));

        /// Assert
        assertEquals("No available physical copy for pickup at this time", res.getMessage());
    }

    @Test
    void TC3_ExpiredReservation() {
        /// Arrange
        var reservation = initReservation(true, false, true);

        /// Act
        var res = assertThrows(RuntimeException.class, () ->
                reservationService.assignBookCopyToReservation(reservation.getId()));

        /// Assert
        assertEquals("Cannot assign book copy to expired reservation", res.getMessage());
    }

    @Test
    void TC4_ExpiredReservation() {
        /// Arrange
        var reservation = initReservation(false, true, true);

        /// Act
        var res = assertThrows(RuntimeException.class, () ->
                reservationService.assignBookCopyToReservation(reservation.getId()));

        /// Assert
        assertEquals("Reservation not found", res.getMessage());
    }

    @Test
    void TC5_OK_ExactDay() {
        /// Arrange
        var reservation = initReservation(true, true, true);
        reservation.setReservationDate(LocalDate.now());
        /// Act
        var res = reservationService.assignBookCopyToReservation(reservation.getId());

        /// Assert
        // dto
        assertEquals(reservation.getBookCopyId(), res.getBookCopyId());
        // repository save
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertEquals(reservation.getBookCopyId(), captor.getValue().getBookCopyId());
    }

    BookTitle initBookTitle() {
        var bookTitle = new BookTitle();
        bookTitle.setId("book-title-id");
        bookTitle.setTitle("book-title");
        var author = new BookAuthor();
        author.setAuthor(new Author());
        bookTitle.setBookAuthors(List.of(author));
        return bookTitle;
    }
    Reservation initReservation(
            boolean reservationExists,
            boolean reservationNotExpired,
            boolean availableBookCopyExists
    ) {
        var reservationId = "reservation-id";
        var reservation = new Reservation();
        reservation.setId(reservationId);
        if (!reservationExists) {
            when(reservationRepository.findById(reservationId))
                    .thenReturn(Optional.empty());
            return reservation;
        }
        var bookTitle = initBookTitle();

        if (reservationNotExpired)
            reservation.setExpirationDate(LocalDate.now().plusDays(1));
        else {
            reservation.setExpirationDate(LocalDate.now().minusDays(1));
        }
        reservation.setBookTitleId(bookTitle.getId());
        reservation.setBookTitle(bookTitle);
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
