package com.skybook.common.error;

import java.io.Serial;
import java.util.Map;

/** Thrown when a resource addressed by the client does not exist (or is not visible to the caller). */
public class ResourceNotFoundException extends SkyBookException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(CommonErrorCode.RESOURCE_NOT_FOUND,
                "%s '%s' was not found".formatted(resourceType, resourceId),
                Map.of("resourceType", resourceType, "resourceId", String.valueOf(resourceId)),
                null);
    }
}
