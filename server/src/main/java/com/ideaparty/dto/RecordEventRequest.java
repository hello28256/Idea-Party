package com.ideaparty.dto;

import com.ideaparty.entity.MessageEvent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordEventRequest {

    @NotNull(message = "event type is required")
    private MessageEvent.EventType eventType;

    /** Optional, only meaningful for READ_COMPLETE / FOCUS. */
    @PositiveOrZero
    private Integer dwellMs;

    @Size(max = 4000)
    private String metadata;
}
