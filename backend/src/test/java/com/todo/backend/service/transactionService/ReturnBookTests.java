package com.todo.backend.service.transactionService;

import com.todo.backend.dao.*;
import com.todo.backend.dto.transaction.ReturnBookDto;
import com.todo.backend.dto.transaction.ReturnBookResponseDto;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReturnBookTests {

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
    void TC1_TransactionNotFound() {
        /// Arrange
        var transactionId = "non-existent-transaction-id";
        var returnDto = ReturnBookDto.builder()
                .returnedDate(LocalDate.now())
                .bookCondition(BookCopyCondition.GOOD)
                .additionalPenaltyFee(0)
                .build();

        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.empty());

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.returnBook(transactionId, returnDto, false));

        /// Assert
        assertEquals("Transaction with ID not found", exception.getMessage());
    }

    @Test
    void TC2_AlreadyReturned() {
        /// Arrange
        var transactionId = "transaction-id";
        var transaction = new Transaction();
        transaction.setReturnedDate(LocalDate.now().minusDays(1)); // Already returned

        var returnDto = ReturnBookDto.builder()
                .returnedDate(LocalDate.now())
                .bookCondition(BookCopyCondition.GOOD)
                .additionalPenaltyFee(0)
                .build();

        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(transaction));

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.returnBook(transactionId, returnDto, false));

        /// Assert
        assertEquals("Book has already been returned", exception.getMessage());
    }

    @Test
    void TC3_BookCopyNotFound() {
        /// Arrange
        var transactionId = "transaction-id";
        var transaction = new Transaction();
        transaction.setBookCopyId("non-existent-book-copy-id");
        transaction.setReturnedDate(null);

        var returnDto = ReturnBookDto.builder()
                .returnedDate(LocalDate.now())
                .bookCondition(BookCopyCondition.GOOD)
                .additionalPenaltyFee(0)
                .build();

        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(transaction));
        when(bookCopyRepository.findById(transaction.getBookCopyId()))
                .thenReturn(Optional.empty());

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.returnBook(transactionId, returnDto, false));

        /// Assert
        assertTrue(exception.getMessage().contains("BookCopy with ID"));
        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    void TC4_UserNotFound() {
        /// Arrange
        var transactionId = "transaction-id";
        var transaction = new Transaction();
        transaction.setBookCopyId("book-copy-id");
        transaction.setUserId("non-existent-user-id");
        transaction.setReturnedDate(null);

        var bookCopy = initBookCopy("book-copy-id", 100000);
        var returnDto = ReturnBookDto.builder()
                .returnedDate(LocalDate.now())
                .bookCondition(BookCopyCondition.GOOD)
                .additionalPenaltyFee(0)
                .build();

        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(transaction));
        when(bookCopyRepository.findById(transaction.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));
        when(userRepository.findById(transaction.getUserId()))
                .thenReturn(Optional.empty());

        /// Act
        var exception = assertThrows(RuntimeException.class, () ->
                transactionService.returnBook(transactionId, returnDto, false));

        /// Assert
        assertTrue(exception.getMessage().contains("User with ID"));
        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    void TC5_OnTime_FullRefund() {
        /// Arrange
        var result = executeReturnBook(
                LocalDate.now().minusDays(10), // borrowed 10 days ago
                LocalDate.now().plusDays(4),   // due in 4 days
                LocalDate.now(),                // return today (early)
                BookCopyCondition.GOOD,
                0,
                false
        );

        /// Assert
        assertEquals(0, result.getTotalPenaltyFee());
        assertEquals(100000, result.getRefundAmount());
        assertUserBalance(150000); // 50000 + 100000 refund
        verify(balanceTransactionService).logTransaction(
                anyString(),
                eq(BalanceTransactionType.REFUND),
                eq(100000),
                anyString(),
                eq(150000)
        );
    }

    @Test
    void TC6_OnDueDate_FullRefund() {
        /// Arrange
        var dueDate = LocalDate.now();
        var result = executeReturnBook(
                dueDate.minusWeeks(2),  // borrowed 2 weeks ago
                dueDate,                 // due today
                dueDate,                 // return today
                BookCopyCondition.GOOD,
                0,
                false
        );

        /// Assert
        assertEquals(0, result.getTotalPenaltyFee());
        assertEquals(100000, result.getRefundAmount());
        assertUserBalance(150000);
    }

    @Test
    void TC7_OneDayLate() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(1);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),         // 1 day late
                BookCopyCondition.GOOD,
                0,
                false
        );

        /// Assert
        assertEquals(5000, result.getTotalPenaltyFee());
        assertEquals(95000, result.getRefundAmount());
        assertUserBalance(145000); // 50000 + 95000 refund
        assertTransactionDetailCreated(5000);
    }

    @Test
    void TC8_FiveDaysLate() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(5);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),
                BookCopyCondition.GOOD,
                0,
                false
        );

        /// Assert
        assertEquals(25000, result.getTotalPenaltyFee());
        assertEquals(75000, result.getRefundAmount());
        assertUserBalance(125000);
        assertTransactionDetailCreated(25000);
    }

    @Test
    void TC9_TenDaysLate() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(10);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),
                BookCopyCondition.GOOD,
                0,
                false
        );

        /// Assert
        assertEquals(50000, result.getTotalPenaltyFee());
        assertEquals(50000, result.getRefundAmount());
        assertUserBalance(100000);
        assertTransactionDetailCreated(50000);
    }

    @Test
    void TC10_TwentyFiveDaysLate_Capped() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(25);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),
                BookCopyCondition.GOOD,
                0,
                false
        );

        /// Assert
        assertEquals(100000, result.getTotalPenaltyFee()); // Capped at book price
        assertEquals(0, result.getRefundAmount());
        assertUserBalance(50000); // No change
        assertTransactionDetailCreated(100000);
    }

    @Test
    void TC11_TwentyDaysLate_MinorDamage() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(20);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),
                BookCopyCondition.DAMAGED,
                20000,
                false
        );

        /// Assert
        assertEquals(120000, result.getTotalPenaltyFee()); // 100k capped + 20k damage
        assertEquals(0, result.getRefundAmount());
        assertUserBalance(30000); // 50000 - 20000 extra charge
        assertTransactionDetailCreated(120000);
        verify(balanceTransactionService).logTransaction(
                anyString(),
                eq(BalanceTransactionType.PENALTY_FEE),
                eq(-20000),
                anyString(),
                eq(30000)
        );
    }

    @Test
    void TC12_TwentyDaysLate_SevereDamage() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(20);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),
                BookCopyCondition.DAMAGED,
                50000,
                false
        );

        /// Assert
        assertEquals(150000, result.getTotalPenaltyFee()); // 100k capped + 50k damage
        assertEquals(0, result.getRefundAmount());
        assertUserBalance(0); // 50000 - 50000 extra charge
        assertTransactionDetailCreated(150000);
    }

    @Test
    void TC13_LostBook() {
        /// Arrange
        var dueDate = LocalDate.now().minusDays(25);
        var result = executeReturnBook(
                dueDate.minusWeeks(2),
                dueDate,
                LocalDate.now(),
                BookCopyCondition.GOOD, // Doesn't matter for lost books
                0,
                true // Lost book
        );

        /// Assert
        assertEquals(200000, result.getTotalPenaltyFee()); // 100k late + 100k replacement
        assertEquals(0, result.getRefundAmount());
        assertUserBalance(-50000); // 50000 - 100000 extra charge
        assertTransactionDetailCreated(200000);
        verify(balanceTransactionService).logTransaction(
                anyString(),
                eq(BalanceTransactionType.PENALTY_FEE),
                eq(-100000),
                anyString(),
                eq(-50000)
        );
        // Verify book copy status set to LOST
        ArgumentCaptor<BookCopy> bookCopyCaptor = ArgumentCaptor.forClass(BookCopy.class);
        verify(bookCopyRepository).save(bookCopyCaptor.capture());
        assertEquals(BookCopyStatus.LOST, bookCopyCaptor.getValue().getStatus());
    }

    // Helper methods
    private ReturnBookResponseDto executeReturnBook(
            LocalDate borrowDate,
            LocalDate dueDate,
            LocalDate returnDate,
            BookCopyCondition bookCondition,
            int additionalPenalty,
            boolean isLost
    ) {
        var transactionId = "transaction-id";
        var transaction = new Transaction();
        transaction.setId(transactionId);
        transaction.setUserId("user-id");
        transaction.setBookCopyId("book-copy-id");
        transaction.setBorrowDate(borrowDate);
        transaction.setDueDate(dueDate);
        transaction.setReturnedDate(null);

        var user = new User();
        user.setId("user-id");
        user.setBalance(50000);
        user.setName("Test User");

        var bookCopy = initBookCopy("book-copy-id", 100000);

        var returnDto = ReturnBookDto.builder()
                .returnedDate(returnDate)
                .bookCondition(bookCondition)
                .additionalPenaltyFee(additionalPenalty)
                .description("Test return")
                .build();

        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(transaction));
        when(bookCopyRepository.findById(transaction.getBookCopyId()))
                .thenReturn(Optional.of(bookCopy));
        when(userRepository.findById(transaction.getUserId()))
                .thenReturn(Optional.of(user));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bookCopyRepository.save(any(BookCopy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionDetailRepository.save(any(TransactionDetail.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        return transactionService.returnBook(transactionId, returnDto, isLost);
    }

    private BookCopy initBookCopy(String bookCopyId, int price) {
        var bookCopy = new BookCopy();
        bookCopy.setId(bookCopyId);
        bookCopy.setStatus(BookCopyStatus.BORROWED);

        var bookTitle = new BookTitle();
        bookTitle.setId("book-title-id");
        bookTitle.setTitle("Test Book");
        bookTitle.setPrice(price);

        bookCopy.setBookTitle(bookTitle);
        return bookCopy;
    }

    private void assertUserBalance(int expectedBalance) {
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(expectedBalance, userCaptor.getValue().getBalance());
    }

    private void assertTransactionDetailCreated(int expectedPenalty) {
        ArgumentCaptor<TransactionDetail> detailCaptor = ArgumentCaptor.forClass(TransactionDetail.class);
        verify(transactionDetailRepository).save(detailCaptor.capture());
        assertEquals(expectedPenalty, detailCaptor.getValue().getPenaltyFee());
    }
}
