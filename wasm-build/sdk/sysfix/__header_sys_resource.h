#ifndef __wasilibc___header_sys_resource_h
#define __wasilibc___header_sys_resource_h

#include <__struct_rusage.h>

#define RUSAGE_SELF 1
#define RUSAGE_CHILDREN 2

#ifdef __cplusplus
extern "C" {
#endif

// getrusage declaration is provided by patch.h (force-included)
// to avoid conflicting types with the void* stub implementation.

#ifdef __cplusplus
}
#endif

#endif
