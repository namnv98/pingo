package com.pingo.core.common.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NonNull
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class SortPair {

    private String field;
    private SortType sortType;

}
