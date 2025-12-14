package com.todo.backend.service;

import com.todo.backend.dao.BookCopyRepository;
import com.todo.backend.dao.BookTitleRepository;
import com.todo.backend.dao.TransactionRepository;
import com.todo.backend.dao.UserRepository;
import com.todo.backend.dto.bookcopy.CreateBookCopyDto;
import com.todo.backend.dto.bookcopy.ResponseBookCopyDto;
import com.todo.backend.dto.bookcopy.UpdateBookCopyDto;
import com.todo.backend.entity.*;
import com.todo.backend.entity.identity.UserRole;
import com.todo.backend.mapper.BookCopyMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class BookCopyService {
    private final BookCopyRepository bookCopyRepository;
    private final BookTitleRepository bookTitleRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final BookCopyMapper bookCopyMapper;

    public List<ResponseBookCopyDto> getAllBookCopies() {
        List<BookCopy> bookCopies = bookCopyRepository.findAll();
        return bookCopies.stream()
                .map(this::buildEnhancedBookCopyDto)
                .toList();
    }

    public ResponseBookCopyDto getBookCopyDto(String id) {
        BookCopy bookCopy = getBookCopy(id);
        return buildEnhancedBookCopyDto(bookCopy);
    }

    public BookCopy getBookCopy(String id) {
        return bookCopyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("BookCopy with this ID does not exist"));
    }

    private ResponseBookCopyDto buildEnhancedBookCopyDto(BookCopy bookCopy) {
        ResponseBookCopyDto.ResponseBookCopyDtoBuilder builder = ResponseBookCopyDto.builder()
                .id(bookCopy.getId())
                .bookTitleId(bookCopy.getBookTitleId())
                .status(bookCopy.getStatus())
                .condition(bookCopy.getCondition()); // Add the condition field

        // Add book title information
        if (bookCopy.getBookTitle() != null) {
            builder.bookTitle(bookCopy.getBookTitle().getTitle())
                   .bookPhotoUrl(bookCopy.getBookTitle().getImageUrl())
                   .bookPrice(bookCopy.getBookTitle().getPrice());
        }

        // Add borrower information if the book is currently borrowed
        if (BookCopyStatus.BORROWED.equals(bookCopy.getStatus())) {
            // Find the current borrower through transactions
            List<Transaction> unreturned = transactionRepository
                    .findByBookCopyIdAndReturnedDateIsNull(bookCopy.getId());

            if (!unreturned.isEmpty()) {
                Transaction currentTransaction = unreturned.getFirst();
                userRepository.findById(currentTransaction.getUserId())
                        .ifPresent(borrower -> builder.borrowerCccd(borrower.getCccd())
                        .borrowerName(borrower.getName())
                        .borrowerId(borrower.getId()));
            }
        }

        return builder.build();
    }

    public List<ResponseBookCopyDto> createBookCopies(CreateBookCopyDto bookCopyDto) {
        if (!bookTitleRepository.existsById(bookCopyDto.getBookTitleId())) {
            throw new IllegalArgumentException("BookTitle with this ID does not exist");
        }

        // Create a new BookCopy entity for each copy requested
        var copies = new ArrayList<ResponseBookCopyDto>();
        for (int i = 0; i < bookCopyDto.getQuantity(); i++) {
            BookCopy bookCopy = new BookCopy();
            bookCopy.setBookTitleId(bookCopyDto.getBookTitleId());
            var condition = bookCopyDto.getCondition();
            if (condition == null) {
                condition = BookCopyCondition.NEW; // Default condition if not provided
            }
            bookCopy.setStatus(BookCopyStatus.AVAILABLE);
            bookCopy.setCondition(condition); // Set default condition for new book copies or you could have bookCopy condition in the dto idk
            bookCopyRepository.save(bookCopy);
            copies.add(buildEnhancedBookCopyDto(bookCopy));
        }

        // Return the enhanced DTO for the created copy
        return copies;
    }

    public void deleteBookCopy(String id) {
        BookCopy existingBookCopy = bookCopyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("BookCopy with this ID does not exist"));

        bookCopyRepository.delete(existingBookCopy);
    }

    public ResponseBookCopyDto updateBookCopy(String id, UpdateBookCopyDto updateBookCopyDto, String currentUserId) {
        BookCopy existingBookCopy = bookCopyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("BookCopy with this ID does not exist"));

        // Get current user's role for validation
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Current user not found"));
        
        // Validate business rules based on role
        validateBookCopyUpdate(existingBookCopy, updateBookCopyDto, currentUser.getRole());

        // Update the fields using the mapper
        bookCopyMapper.updateEntityFromUpdateDto(updateBookCopyDto, existingBookCopy);
        
        bookCopyRepository.save(existingBookCopy);

        return buildEnhancedBookCopyDto(existingBookCopy);
    }

    private void validateBookCopyUpdate(BookCopy existingBookCopy, UpdateBookCopyDto updateDto, UserRole currentUserRole) {
        // Role-based validation: Librarians can only change status when book is NOT borrowed
        if (currentUserRole == UserRole.LIBRARIAN) {
            // Check if the status is being changed
            if (!existingBookCopy.getStatus().equals(updateDto.getStatus())) {
                // Librarian cannot change status if book is currently borrowed
                if (existingBookCopy.getStatus() == BookCopyStatus.BORROWED) {
                    throw new IllegalArgumentException("Librarians cannot change book status while the book is borrowed");
                }
            }
        }
        // Note: ADMIN can do whatever they want - no additional restrictions

        // Existing business rules (apply to all roles)
        // Cannot change status to BORROWED if there are no active transactions
        if (updateDto.getStatus() == BookCopyStatus.BORROWED) {
            List<Transaction> unreturned = transactionRepository
                    .findByBookCopyIdAndReturnedDateIsNull(existingBookCopy.getId());
            if (unreturned.isEmpty()) {
                throw new IllegalArgumentException("Cannot set status to BORROWED without an active transaction");
            }
        }

        // Cannot change status from BORROWED to other status if there are active transactions
        if (existingBookCopy.getStatus() == BookCopyStatus.BORROWED && 
            updateDto.getStatus() != BookCopyStatus.BORROWED) {
            List<Transaction> unreturned = transactionRepository
                    .findByBookCopyIdAndReturnedDateIsNull(existingBookCopy.getId());
            if (!unreturned.isEmpty()) {
                throw new IllegalArgumentException("Cannot change status from BORROWED while there are active transactions. Return the book first.");
            }
        }
    }
}
