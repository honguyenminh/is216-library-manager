package com.todo.backend.service.transactionService;

import com.todo.backend.dao.*;
import com.todo.backend.dto.transaction.CreateTransactionDto;
import com.todo.backend.entity.*;
import com.todo.backend.mapper.TransactionDetailMapper;
import com.todo.backend.mapper.TransactionMapper;
import com.todo.backend.service.BalanceTransactionService;
import com.todo.backend.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CreateTransactionTests {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionDetailRepository transactionDetailRepository;
    @Mock
    private BookCopyRepository bookCopyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private BalanceTransactionService balanceTransactionService;
    @Spy
    private TransactionMapper transactionMapper = Mappers.getMapper(TransactionMapper.class);
    @Spy
    private TransactionDetailMapper transactionDetailMapper = Mappers.getMapper(TransactionDetailMapper.class);

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void TC1_OK() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("book-copy-id");
        createDto.setDueDate(LocalDate.now().plusWeeks(2));

        var user = initUser(createDto.getUserId(), 150000);
        var bookCopy = initBookCopy(createDto.getBookCopyId(), "book-title-id", 100000, BookCopyStatus.AVAILABLE, true);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));
        when(transactionRepository.findByUserIdAndReturnedDateIsNull(createDto.getUserId()))
                .thenReturn(List.of());
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bookCopyRepository.save(any(BookCopy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        /// Act
        var res = transactionService.createTransaction(createDto);

        /// Assert
        // dto
        assertEquals(createDto.getUserId(), res.getUserId());
        assertEquals(createDto.getBookCopyId(), res.getBookCopyId());
        // transaction saved
        verify(transactionRepository).save(any(Transaction.class));
        // book copy status updated
        ArgumentCaptor<BookCopy> bookCopyCaptor = ArgumentCaptor.forClass(BookCopy.class);
        verify(bookCopyRepository).save(bookCopyCaptor.capture());
        assertEquals(BookCopyStatus.BORROWED, bookCopyCaptor.getValue().getStatus());
        // user balance deducted
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(50000, userCaptor.getValue().getBalance()); // 150000 - 100000
        // balance transaction logged
        verify(balanceTransactionService).logTransaction(
                eq(user.getId()),
                eq(BalanceTransactionType.BOOK_RENTAL),
                eq(-100000),
                anyString(),
                eq(50000)
        );
    }

    @Test
    void TC2_InsufficientBalance() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("book-copy-id");

        var user = initUser(createDto.getUserId(), 50000);
        var bookCopy = initBookCopy(createDto.getBookCopyId(), "book-title-id", 100000, BookCopyStatus.AVAILABLE, true);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.createTransaction(createDto));

        /// Assert
        assertTrue(exception.getMessage().contains("Insufficient balance"));
    }

    @Test
    void TC3_BookCopyNotAvailable() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("book-copy-id");

        var user = initUser(createDto.getUserId(), 150000);
        var bookCopy = initBookCopy(createDto.getBookCopyId(), "book-title-id", 100000, BookCopyStatus.BORROWED, true);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.createTransaction(createDto));

        /// Assert
        assertTrue(exception.getMessage().contains("is not available"));
    }

    @Test
    void TC4_CannotBeBorrowed() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("book-copy-id");

        var user = initUser(createDto.getUserId(), 150000);
        var bookCopy = initBookCopy(createDto.getBookCopyId(), "book-title-id", 100000, BookCopyStatus.AVAILABLE, false);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.createTransaction(createDto));

        /// Assert
        assertEquals("This book title cannot be borrowed", exception.getMessage());
    }

    @Test
    void TC5_UserNotFound() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("non-existent-user-id");
        createDto.setBookCopyId("book-copy-id");

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.empty());

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.createTransaction(createDto));

        /// Assert
        assertTrue(exception.getMessage().contains("User with ID"));
        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    void TC6_BookCopyNotFound() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("non-existent-book-copy-id");

        var user = initUser(createDto.getUserId(), 150000);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.empty());

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.createTransaction(createDto));

        /// Assert
        assertTrue(exception.getMessage().contains("BookCopy with ID"));
        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    void TC7_OneCopyPerTitleAllowed() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("book-copy-id-2");

        var user = initUser(createDto.getUserId(), 150000);
        var bookCopy = initBookCopy(createDto.getBookCopyId(), "book-title-id", 100000, BookCopyStatus.AVAILABLE, true);

        // User already has a book from the same title borrowed
        var existingTransaction = new Transaction();
        existingTransaction.setBookCopyId("book-copy-id-1");
        var existingBookCopy = initBookCopy("book-copy-id-1", "book-title-id", 100000, BookCopyStatus.BORROWED, true);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));
        when(transactionRepository.findByUserIdAndReturnedDateIsNull(createDto.getUserId()))
                .thenReturn(List.of(existingTransaction));
        when(bookCopyRepository.findById("book-copy-id-1"))
                .thenReturn(Optional.of(existingBookCopy));

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.createTransaction(createDto));

        /// Assert
        assertEquals("Only one book copy per book title is allowed", exception.getMessage());
    }

    @Test
    void TC8_BalanceEqualsPrice() {
        /// Arrange
        var createDto = new CreateTransactionDto();
        createDto.setUserId("user-id");
        createDto.setBookCopyId("book-copy-id");
        createDto.setDueDate(LocalDate.now().plusWeeks(2));

        var user = initUser(createDto.getUserId(), 100000);
        var bookCopy = initBookCopy(createDto.getBookCopyId(), "book-title-id", 100000, BookCopyStatus.AVAILABLE, true);

        when(userRepository.findById(createDto.getUserId()))
                .thenReturn(Optional.of(user));
        when(bookCopyRepository.findById(createDto.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));
        when(transactionRepository.findByUserIdAndReturnedDateIsNull(createDto.getUserId()))
                .thenReturn(List.of());
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bookCopyRepository.save(any(BookCopy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        /// Act
        var res = transactionService.createTransaction(createDto);

        /// Assert
        // user balance deducted to 0
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(0, userCaptor.getValue().getBalance());
    }

    // Helper methods
    private User initUser(String userId, int balance) {
        var user = new User();
        user.setId(userId);
        user.setBalance(balance);
        user.setName("Test User");
        return user;
    }

    private BookCopy initBookCopy(String bookCopyId, String bookTitleId, int price, BookCopyStatus status, boolean canBorrow) {
        var bookCopy = new BookCopy();
        bookCopy.setId(bookCopyId);
        bookCopy.setBookTitleId(bookTitleId);
        bookCopy.setStatus(status);

        var bookTitle = new BookTitle();
        bookTitle.setId(bookTitleId);
        bookTitle.setTitle("Test Book");
        bookTitle.setPrice(price);
        bookTitle.setCanBorrow(canBorrow);

        bookCopy.setBookTitle(bookTitle);
        return bookCopy;
    }
}
