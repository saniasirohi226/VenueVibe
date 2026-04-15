package com.mood.recommender.model;

import lombok.Data;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

@Data
public class RankRequest {
    private List<ObjectNode> venues;
    private ObjectNode moodProfile;
}
