// Authors: Стефан Колесников, GPT-6.1 Sol.
#![deny(unsafe_code)]

mod render;

// The C ABI is the only module that handles raw pointers.
#[allow(unsafe_code)]
mod ffi {
    use super::render::{self, Error, MAX_FRAME};
    use std::{panic::catch_unwind, ptr, slice};

    // ABI v1: little-endian request and response. Return a positive response size,
    // including when capacity is too small and no bytes were written, or a negative error.
    // Caller guarantees a live, initialized input allocation and a distinct writable
    // output allocation for the full lengths, with no concurrent mutation. FFM
    // supplies them from one confined arena on the calling thread. Address checks
    // below can reject overlap and wraparound, but cannot establish pointer validity.
    // Rust never retains either pointer or transfers an allocation.
    #[unsafe(no_mangle)]
    unsafe extern "C" fn onionall_content_render_v1(
        input: *const u8,
        length: i32,
        output: *mut u8,
        capacity: i32,
    ) -> i32 {
        if input.is_null()
            || output.is_null()
            || !(1..=MAX_FRAME as i32).contains(&length)
            || !(1..=MAX_FRAME as i32).contains(&capacity)
        {
            return -1;
        }
        let Some(input_end) = input.addr().checked_add(length as usize) else {
            return -1;
        };
        let Some(output_end) = output.addr().checked_add(capacity as usize) else {
            return -1;
        };
        if input.addr() < output_end && output.addr() < input_end {
            return -1;
        }
        // SAFETY: the caller guarantees initialized storage in one live allocation.
        // u8 requires alignment 1 and the 2 MiB limit is below isize::MAX.
        let bytes = unsafe { slice::from_raw_parts(input, length as usize) };
        match catch_unwind(|| render::document(bytes)) {
            Ok(Ok(response)) => {
                if response.len() <= capacity as usize {
                    // SAFETY: the caller owns a distinct writable output allocation;
                    // its capacity was checked, and this call retains neither pointer.
                    unsafe { ptr::copy_nonoverlapping(response.as_ptr(), output, response.len()) };
                }
                response.len() as i32
            }
            Ok(Err(Error::Malformed)) => -1,
            Ok(Err(Error::Version)) => -2,
            Ok(Err(Error::FrameLimit)) => -4,
            Err(_) => -3,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn checks_version_and_capacity_without_writing_a_partial_frame() {
            let request = b"OCM1\x01\xff\x00\x00\x00t\x02\x00\x00\x00ok";
            let mut output = [0u8; 32];
            let render = |frame: &[u8], out: &mut [u8]| {
                // SAFETY: both slices are live, and the mutable slice is exclusive.
                unsafe {
                    onionall_content_render_v1(
                        frame.as_ptr(),
                        frame.len() as i32,
                        out.as_mut_ptr(),
                        out.len() as i32,
                    )
                }
            };
            assert_eq!(render(b"OCM2", &mut output), -2);
            assert_eq!(render(b"OCM1", &mut output), -1);
            assert_eq!(render(request, &mut output[..5]), 12);
            assert_eq!(output, [0; 32]);
            assert_eq!(render(request, &mut output), 12);
            assert_eq!(&output[..12], b"OCM1\x01\x00\x02\x00\x00\x00ok");
        }

        #[test]
        fn overlapping_ranges_are_rejected_before_reading_or_writing() {
            let mut data = *b"OCM1\x01\xff\x00\x00\x00t\x02\x00\x00\x00ok";
            let ptr = data.as_mut_ptr();
            // Both ranges refer to live storage. The ABI checks their overlap
            // before constructing a slice or writing any bytes.
            let result = unsafe { onionall_content_render_v1(ptr, 16, ptr.add(1), 15) };
            assert_eq!(result, -1);
            assert_eq!(&data, b"OCM1\x01\xff\x00\x00\x00t\x02\x00\x00\x00ok");
        }
    }
}
