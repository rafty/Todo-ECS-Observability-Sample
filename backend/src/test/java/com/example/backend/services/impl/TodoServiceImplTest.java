package com.example.backend.services.impl;

import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.TodoNotFoundException;
import com.example.backend.logging.OwnerSubjectHashService;
import com.example.backend.model.Todo;
import com.example.backend.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// なぜ必要か: Service層のowner境界とページング上限制約を単体で固定し、Controller変更時の回帰を防ぐため。
@ExtendWith(MockitoExtension.class)
class TodoServiceImplTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private OwnerSubjectHashService ownerSubjectHashService;

    @InjectMocks
    private TodoServiceImpl todoService;

    @Test
    void shouldCapPageSizeToHundred() {
        when(todoRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        todoService.listTodos("owner-a", null, null, 0, 200, "updatedAt,desc");

        // なぜ必要か: size上限100の契約が守られることをRepository呼び出し引数で保証するため。
        final ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(todoRepository).findAll(any(Specification.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void shouldThrowNotFoundWhenOwnerDoesNotMatch() {
        when(todoRepository.findByIdAndOwnerSubject(99L, "owner-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.getTodo("owner-a", 99L))
                .isInstanceOf(TodoNotFoundException.class);
    }

    @Test
    void shouldSaveCreatedTodoWithDefaultCompletedFalse() {
        when(todoRepository.save(any(Todo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final Todo createdTodo = todoService.createTodo("owner-a", "title", "desc", null);

        // なぜ必要か: completed未指定時の既定値をfalseへ固定し、クライアント差分で挙動がぶれないようにするため。
        assertThat(createdTodo.getOwnerSubject()).isEqualTo("owner-a");
        assertThat(createdTodo.isCompleted()).isFalse();
    }

    @Test
    void shouldThrowBadRequestForUnsupportedSortField() {
        assertThatThrownBy(() -> todoService.listTodos("owner-a", null, null, 0, 20, "unknown,asc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported sort field");
    }

    @Test
    void shouldDeleteOwnedTodo() {
        when(todoRepository.existsByIdAndOwnerSubject(10L, "owner-a")).thenReturn(true);

        todoService.deleteTodo("owner-a", 10L);

        // なぜ必要か: 削除前の所有者チェックを通過したときのみ削除実行されることを明示するため。
        verify(todoRepository).existsByIdAndOwnerSubject(10L, "owner-a");
        verify(todoRepository).deleteByIdAndOwnerSubject(10L, "owner-a");
    }

    @Test
    void shouldThrowNotFoundWhenDeletingOthersTodo() {
        when(todoRepository.existsByIdAndOwnerSubject(10L, "owner-a")).thenReturn(false);

        assertThatThrownBy(() -> todoService.deleteTodo("owner-a", 10L))
                .isInstanceOf(TodoNotFoundException.class);
        verify(todoRepository).existsByIdAndOwnerSubject(10L, "owner-a");
    }
}
