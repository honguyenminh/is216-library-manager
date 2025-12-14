package com.todo.backend.service.bookCopyService;

import com.todo.backend.dao.BookCopyRepository;
import com.todo.backend.dao.TransactionRepository;
import com.todo.backend.dao.UserRepository;
import com.todo.backend.dto.bookcopy.UpdateBookCopyDto;
import com.todo.backend.entity.BookCopy;
import com.todo.backend.entity.BookCopyCondition;
import com.todo.backend.entity.BookCopyStatus;
import com.todo.backend.entity.Transaction;
import com.todo.backend.entity.User;
import com.todo.backend.entity.identity.UserRole;
import com.todo.backend.mapper.BookCopyMapper;
import com.todo.backend.service.BookCopyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UpdateBookTests {

    @Mock
    private BookCopyRepository bookCopyRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private UserRepository userRepository;
    @Spy
    private BookCopyMapper bookCopyMapper = Mappers.getMapper(BookCopyMapper.class);

    @InjectMocks
    private BookCopyService bookCopyService;

    @Test
    void TC1_BookCopyDoesntExist() {
        /// Arrange
        // non-existent book copy
        var testBookCopyId = "non-existent-book-copy-id";
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.empty());
        // empty dto
        var updateDto = new UpdateBookCopyDto();

        /// Act & Assert exception
        var exception = assertThrows(IllegalArgumentException.class, () ->
                bookCopyService.updateBookCopy(testBookCopyId, updateDto, "admin-user-id"));

        /// Assert
        assertEquals("BookCopy with this ID does not exist", exception.getMessage());
    }

    @Test
    void TC2_UserDoesntExist() {
        /// Arrange
        // book copy
        var testBookCopyId = "book-copy-id";
        var bookCopy = new BookCopy();
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.of(bookCopy));
        // invalid user
        var testUserId = "test-user-id";
        when(userRepository.findById(testUserId))
                .thenReturn(Optional.empty());
        // empty dto
        var updateDto = new UpdateBookCopyDto();

        /// Act & Assert exception
        var exception = assertThrows(IllegalArgumentException.class, () ->
                bookCopyService.updateBookCopy(testBookCopyId, updateDto, testUserId));

        /// Assert
        assertEquals("Current user not found", exception.getMessage());
    }

    @Test
    void TC3_Librarian_ToDamaged() {
        /// Arrange
        // book copy
        var testBookCopyId = "book-copy-id";
        var bookCopy = new BookCopy();
        bookCopy.setStatus(BookCopyStatus.AVAILABLE);
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.of(bookCopy));
        when (bookCopyRepository.save(any(BookCopy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // user
        var testUserId = "test-user-id";
        var user = new User();
        user.setRole(UserRole.LIBRARIAN);
        when(userRepository.findById(testUserId))
                .thenReturn(Optional.of(user));
        // update dto
        var updateDto = new UpdateBookCopyDto(BookCopyStatus.DAMAGED, BookCopyCondition.NEW);

        /// Act
        var res = bookCopyService.updateBookCopy(testBookCopyId, updateDto, testUserId);

        /// Assert
        // dto
        assertEquals(BookCopyStatus.DAMAGED, res.getStatus());
        // repository save
        ArgumentCaptor<BookCopy> bookCopyCaptor = ArgumentCaptor.forClass(BookCopy.class);
        verify(bookCopyRepository).save(bookCopyCaptor.capture());
        assertEquals(BookCopyStatus.DAMAGED, bookCopyCaptor.getValue().getStatus());
    }

    @Test
    void TC4_Admin_ToBorrowed() {
        /// Arrange
        // book copy
        var testBookCopyId = "book-copy-id";
        var bookCopy = new BookCopy();
        bookCopy.setStatus(BookCopyStatus.AVAILABLE);
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.of(bookCopy));
        // user
        var testUserId = "test-user-id";
        var user = new User();
        user.setRole(UserRole.ADMIN);
        when(userRepository.findById(testUserId))
                .thenReturn(Optional.of(user));
        // update dto
        var updateDto = new UpdateBookCopyDto(BookCopyStatus.BORROWED, BookCopyCondition.NEW);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            bookCopyService.updateBookCopy(testBookCopyId, updateDto, testUserId);
        });

        /// Assert
        assertEquals("Cannot set status to BORROWED without an active transaction", exception.getMessage());
    }

    @Test
    void TC5_Librarian_FromBorrowed() {
        /// Arrange
        // book copy
        var testBookCopyId = "book-copy-id";
        var bookCopy = new BookCopy();
        bookCopy.setStatus(BookCopyStatus.BORROWED);
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.of(bookCopy));
        // user
        var testUserId = "test-user-id";
        var user = new User();
        user.setRole(UserRole.LIBRARIAN);
        when(userRepository.findById(testUserId))
                .thenReturn(Optional.of(user));
        // update dto
        var updateDto = new UpdateBookCopyDto(BookCopyStatus.AVAILABLE, BookCopyCondition.NEW);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                bookCopyService.updateBookCopy(testBookCopyId, updateDto, testUserId));

        /// Assert
        assertEquals("Librarians cannot change book status while the book is borrowed", exception.getMessage());
    }

    @Test
    void TC6_Admin_FromBorrowed() {
        /// Arrange
        // book copy
        var testBookCopyId = "book-copy-id";
        var bookCopy = new BookCopy();
        bookCopy.setId(testBookCopyId);
        bookCopy.setStatus(BookCopyStatus.BORROWED);
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.of(bookCopy));
        // user
        var testUserId = "test-user-id";
        var user = new User();
        user.setRole(UserRole.ADMIN);
        when(userRepository.findById(testUserId))
                .thenReturn(Optional.of(user));
        // active transaction
        when(transactionRepository.findByBookCopyIdAndReturnedDateIsNull(testBookCopyId))
                .thenReturn(List.of(new Transaction()));
        // update dto
        var updateDto = new UpdateBookCopyDto(BookCopyStatus.DAMAGED, BookCopyCondition.NEW);

        /// Act
        var exception = assertThrows(IllegalArgumentException.class, () ->
                bookCopyService.updateBookCopy(testBookCopyId, updateDto, testUserId));

        /// Assert
        assertEquals("Cannot change status from BORROWED while there are active transactions. Return the book first.", exception.getMessage());
    }

    @Test
    void TC7_Admin_ToDamaged() {
        /// Arrange
        // book copy
        var testBookCopyId = "book-copy-id";
        var bookCopy = new BookCopy();
        bookCopy.setId(testBookCopyId);
        bookCopy.setStatus(BookCopyStatus.BORROWED);
        when(bookCopyRepository.findById(testBookCopyId))
                .thenReturn(Optional.of(bookCopy));
        // user
        var testUserId = "test-user-id";
        var user = new User();
        user.setRole(UserRole.ADMIN);
        when(userRepository.findById(testUserId))
                .thenReturn(Optional.of(user));
        // update dto
        var updateDto = new UpdateBookCopyDto(BookCopyStatus.DAMAGED, BookCopyCondition.NEW);

        /// Act
        var res = bookCopyService.updateBookCopy(testBookCopyId, updateDto, testUserId);

        /// Assert
        // dto
        assertEquals(BookCopyStatus.DAMAGED, res.getStatus());
        // repository save
        ArgumentCaptor<BookCopy> bookCopyCaptor = ArgumentCaptor.forClass(BookCopy.class);
        verify(bookCopyRepository).save(bookCopyCaptor.capture());
        assertEquals(BookCopyStatus.DAMAGED, bookCopyCaptor.getValue().getStatus());
    }

}
