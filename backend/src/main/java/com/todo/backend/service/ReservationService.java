package com.todo.backend.service;

import com.todo.backend.dao.BookCopyRepository;
import com.todo.backend.dao.BookTitleRepository;
import com.todo.backend.dao.ReservationRepository;
import com.todo.backend.dao.UserRepository;
import com.todo.backend.dto.reservation.CreateReservationDto;
import com.todo.backend.dto.reservation.ResponseReservationDto;
import com.todo.backend.dto.reservation.UpdateReservationDto;
import com.todo.backend.entity.*;
import com.todo.backend.entity.identity.UserRole;
import com.todo.backend.mapper.ReservationMapper;
import com.todo.backend.scheduler.jobs.ReservationExpiryJob;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.quartz.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class ReservationService {
    private final String RESERVATION_GROUP = "reservationGroup";
    private final String RESERVATION_EXPIRY_JOB_PREFIX = "reservationExpiryJob_";
    private final String RESERVATION_EXPIRY_TRIGGER_PREFIX = "reservationExpiryTrigger_";

    private final ReservationRepository reservationRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BookTitleRepository bookTitleRepository;
    private final UserRepository userRepository;
    private final ReservationMapper reservationMapper;
    private final Scheduler scheduler;

    public ResponseReservationDto getReservation(String id, String userId) {
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reservation not found"));

        boolean isOwner = reservation.getUserId().equals(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        boolean isAdminOrLibrarian = user.getRole().equals(UserRole.ADMIN) || user.getRole().equals(UserRole.LIBRARIAN);
        if (!isOwner && !isAdminOrLibrarian) {
            throw new RuntimeException("You do not have permission to view this reservation");
        }

        return enhanceReservationDto(reservation);
    }

    private ResponseReservationDto enhanceReservationDto(Reservation reservation) {
        ResponseReservationDto dto = reservationMapper.toResponseDto(reservation);
        
        // Get book details
        BookTitle bookTitle = reservation.getBookTitle();
        dto.setBookTitle(bookTitle.getTitle());
        dto.setBookImageUrl(bookTitle.getImageUrl());
        
        // Get author names
        List<String> authorNames = bookTitle.getBookAuthors().stream()
                .map(bookAuthor -> bookAuthor.getAuthor().getName())
                .toList();

        dto.setBookAuthors(authorNames);
        
        return dto;
    }

    public List<ResponseReservationDto> getAllReservations() {
        List<Reservation> reservations = reservationRepository.findAll();
        return reservations.stream()
                .map(this::enhanceReservationDto)
                .toList();
    }

    public List<ResponseReservationDto> getReservationsByUserId(String userId) {
        List<Reservation> reservations = reservationRepository.findByUserId(userId);
        return reservations.stream()
                .map(this::enhanceReservationDto)
                .toList();
    }

    public ResponseReservationDto createReservation(String userId, CreateReservationDto createReservationDto) {
        LocalDate today = LocalDate.now();

        Reservation reservation = reservationMapper.toEntity(createReservationDto);
        reservation.setUserId(userId);

        validateReservationRules(reservation);

        // Check if a user exists
        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));        // Check if a user has enough balances to reserve
        BookTitle bookTitle = bookTitleRepository.findById(createReservationDto.getBookTitleId())
                .orElseThrow(() -> new RuntimeException("Book title not found"));
        int depositAmount = bookTitle.getPrice() / 10; // 10% of book price as deposit
        
        if (user.getBalance() < depositAmount) {
            throw new RuntimeException("User does not have enough balance to reserve. Required deposit: " + 
                String.format("%,d", depositAmount) + " VND");
        }

        // Deduct the deposit from the user's balance
        user.setBalance(user.getBalance() - depositAmount);
        userRepository.save(user);

        // In the hybrid system, we don't reserve specific copies during online reservation
        // BookCopy will be assigned when the user comes to pick up the book
        reservation.setBookCopyId(null); // Will be assigned later during pickup
        reservation.setReservationDate(today);
        reservation.setExpirationDate(today.plusWeeks(1)); // Automatically set expiration to 1 week from reservation date
        reservation.setDeposit(depositAmount); // Set the calculated deposit

        reservationRepository.save(reservation);

        // Only create the job if the transaction is committed successfully
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                TransactionSynchronization.super.afterCommit();
                createExpiryJob(reservation);
            }
        });

        return reservationMapper.toResponseDto(reservation);
    }

    public ResponseReservationDto updateReservation(String id, String userId, UpdateReservationDto updateReservationDto) {
        Reservation existingReservation = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reservation not found"));

        // ADMIN, LIBRARIAN or the user who created the reservation can update it
        boolean isOwner = existingReservation.getUserId().equals(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        boolean isAdminOrLibrarian = user.getRole().equals(UserRole.ADMIN) || user.getRole().equals(UserRole.LIBRARIAN);
        if (!isOwner && !isAdminOrLibrarian) {
            throw new RuntimeException("You do not have permission to update this reservation");
        }

        // Check if reservation is expired
        LocalDate today = LocalDate.now();
        if (existingReservation.getExpirationDate().isBefore(today)) {
            throw new RuntimeException("Can't update an expired reservation");
        }
        
        // Update the reservation details (deposits are automatically managed based on book prices)
        reservationMapper.updateEntityFromDto(updateReservationDto, existingReservation);
        reservationRepository.save(existingReservation);

        return reservationMapper.toResponseDto(existingReservation);
    }

    public void deleteReservation(String id, String currentUserId) {
        Reservation existingReservation = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reservation not found"));

        // just owner or ADMIN/LIBRARY can delete
        boolean isOwner = existingReservation.getUserId().equals(currentUserId);
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        boolean isAdminOrLibrarian =
                user.getRole() == UserRole.ADMIN ||
                        user.getRole() == UserRole.LIBRARIAN;

        if (!isOwner && !isAdminOrLibrarian) {
            throw new AccessDeniedException("Not allowed to delete others' reservations");
        }

        // Check if reservation is expired (expired reservations are auto-cleaned, but if user tries to delete, allow it)
        LocalDate today = LocalDate.now();
        if (existingReservation.getExpirationDate().isAfter(today) || existingReservation.getExpirationDate().isEqual(today)) {
            // Reservation is still active, cancel it and refund deposit
            cancelReservation(existingReservation);
        }

        reservationRepository.delete(existingReservation);
    }

    public void cleanupExpiredReservation(String id) {
        Reservation existingReservation = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reservation not found"));

        LocalDate today = LocalDate.now();
        if (existingReservation.getExpirationDate().isBefore(today)) {
            // Reservation is expired, clean it up
            cancelReservation(existingReservation);
            reservationRepository.delete(existingReservation);
        }
    }

    private void createExpiryJob(Reservation reservation) {
        JobDetail jobDetail = JobBuilder.newJob(ReservationExpiryJob.class)
                .withIdentity(RESERVATION_EXPIRY_JOB_PREFIX + reservation.getId(), RESERVATION_GROUP)
                .usingJobData("reservationId", reservation.getId())
                .usingJobData("retryCount", 0)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(RESERVATION_EXPIRY_TRIGGER_PREFIX + reservation.getId(), RESERVATION_GROUP)
                .startAt(Date.from(reservation.getExpirationDate().atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()))
                .forJob(jobDetail)
                .build();

        try {
            scheduler.scheduleJob(jobDetail, trigger);
        }
        catch (SchedulerException e) {
            throw new RuntimeException("Failed to schedule reservation expiry job: ", e);
        }
    }

    private void deleteScheduledJob(Reservation reservation) {
        JobKey jobKey = new JobKey(RESERVATION_EXPIRY_JOB_PREFIX + reservation.getId(), RESERVATION_GROUP);
        try {
            if (!scheduler.checkExists(jobKey)) {
                return;
            }

            scheduler.deleteJob(jobKey);
        }
        catch (SchedulerException e) {
            throw new RuntimeException("Failed to delete reservation expiry job: ", e);
        }
    }

    private void cancelReservation(Reservation reservation) {
        // In the hybrid system, only restore book copy status if a specific copy was assigned
        if (reservation.getBookCopyId() != null) {
            BookCopy bookCopy = bookCopyRepository.findById(reservation.getBookCopyId())
                    .orElseThrow(() -> new RuntimeException("Book copy not found"));
            bookCopy.setStatus(BookCopyStatus.AVAILABLE);
            bookCopyRepository.save(bookCopy);
        }

        // Return the deposit to the user
        User user = userRepository.findById(reservation.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setBalance(user.getBalance() + reservation.getDeposit());
        userRepository.save(user);

        deleteScheduledJob(reservation);
    }

    /**
     * Assign a specific BookCopy to a reservation when user comes to pick up
     * This converts an online reservation to a physical checkout
     */
    public ResponseReservationDto assignBookCopyToReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new RuntimeException("Reservation not found"));

        // Check if reservation is still active
        LocalDate today = LocalDate.now();
        if (reservation.getExpirationDate().isBefore(today)) {
            throw new RuntimeException("Cannot assign book copy to expired reservation");
        }

        // Find an available book copy for this book title
        BookCopy availableBookCopy = bookCopyRepository.findFirstByBookTitleIdAndStatus(
                reservation.getBookTitleId(), BookCopyStatus.AVAILABLE);
        
        if (availableBookCopy == null) {
            throw new RuntimeException("No available physical copy for pickup at this time");
        }

        // Assign the book copy and update statuses
        reservation.setBookCopyId(availableBookCopy.getId());
        
        reservationRepository.save(reservation);
        bookCopyRepository.save(availableBookCopy);

        return enhanceReservationDto(reservation);
    }

    private void validateReservationRules(Reservation reservation) {
        LocalDate today = LocalDate.now();
        List<Reservation> activeReservations = reservationRepository.findActiveReservationsByUserId(reservation.getUserId(), today);

        // Maximum 5 reservations per user
        final int MAX_RESERVATIONS = 5;
        if (activeReservations.size() >= MAX_RESERVATIONS) {
            throw new RuntimeException("User has reached the maximum number of active reservations");
        }

        // Only one reservation per book title
        for (Reservation activeReservation : activeReservations) {
            if (activeReservation.getBookTitleId().equals(reservation.getBookTitleId())) {
                throw new RuntimeException("User has already reserved this book");
            }
        }

        // Check if the book title can be borrowed
        BookTitle bookTitle = bookTitleRepository.findById(reservation.getBookTitleId())
                .orElseThrow(() -> new RuntimeException("Book title not found"));

        if (!bookTitle.isCanBorrow()) {
            throw new RuntimeException("This book title cannot be reserved");
        }

        // Check online reservation availability using hybrid inventory system
        List<Reservation> bookTitleActiveReservations = reservationRepository.findActiveReservationsByBookTitleId(reservation.getBookTitleId(), today);
        int currentOnlineReservations = bookTitleActiveReservations.size();
        
        if (currentOnlineReservations >= bookTitle.getMaxOnlineReservations()) {
            throw new RuntimeException("No more online reservation slots available for this book");
        }

        // Check if the deposit is valid
        if (reservation.getDeposit() < 0) {
            throw new RuntimeException("Deposit cannot be negative");
        }
    }
}
