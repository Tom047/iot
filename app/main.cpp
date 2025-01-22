#include "platform/Callback.h"
#include "events/EventQueue.h"
#include "ble/BLE.h"
#include "gatt_server_process.h"
#include "mbed-trace/mbed_trace.h"
#include "drivers/PwmOut.h"
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>

using namespace std::literals::chrono_literals;
// Create a local PwmOut so we don't block BLE stack.
PwmOut buzzer(D6);
DigitalOut yellow(D2);
DigitalOut green(D5);
DigitalOut red(D4);

class NoteService : public ble::GattServer::EventHandler {
public:
    static constexpr size_t BUFFER_SIZE = 20;


    NoteService() :
            _note_char(
                    /* Char UUID */ "485f4145-52b9-4644-af1f-7a6b9322490f",
                                    _noteBuffer,
                                    BUFFER_SIZE,
                                    BUFFER_SIZE,
                                    GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_WRITE |
                                    GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_WRITE_WITHOUT_RESPONSE |
                                    GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_NOTIFY
            ),
            _note_service(
                    /* Service UUID */ "51311102-030e-485f-b122-f8f381aa84ed",
                                       _note_char_array,
                                       1
            )
    {
        _note_char_array[0] = &_note_char;
        _note_char.setWriteAuthorizationCallback(this, &NoteService::authorizeWrite);
    }

    void start(BLE &ble, events::EventQueue &event_queue)
    {
        _server = &ble.gattServer();
        _event_queue = &event_queue;

        printf("Registering Note Service...\r\n");
        ble_error_t err = _server->addService(_note_service);
        if (err) {
            printf("Error %u during addService.\r\n", err);
            return;
        }

        _server->setEventHandler(this);
        printf("Note Service registered.\r\n");
    }

private:
    // =============== BLE GATT Event Handling ===============

    void onDataWritten(const GattWriteCallbackParams &params) override
    {
        if (params.handle == _note_char.getValueHandle()) {
            printf("Note data received (len=%u): ", params.len);
            for (size_t i = 0; i < params.len; i++) {
                printf("%02X ", params.data[i]);
            }
            printf("\r\n");

            // Store the data so we can play it periodically
            _seqLen = params.len > BUFFER_SIZE ? BUFFER_SIZE : params.len;
            memcpy(_seqData, params.data, _seqLen);

            playStoredNotes();
        }
    }

    void onDataSent(const GattDataSentCallbackParams &params) override
    {
        printf("Notification data was sent.\r\n");
    }

    void authorizeWrite(GattWriteAuthCallbackParams *auth)
    {
        if (auth->len < 1 || auth->len > BUFFER_SIZE) {
            auth->authorizationReply = AUTH_CALLBACK_REPLY_ATTERR_INVALID_ATT_VAL_LENGTH;
            return;
        }
        auth->authorizationReply = AUTH_CALLBACK_REPLY_SUCCESS;
    }

    // =============== Playback Logic ===============

    void playStoredNotes()
    {
        if (_seqLen == 0) {
            return; // no notes stored
        }

        printf("Playing stored sequence (len=%u)\r\n", _seqLen);
        playNotes(_seqData, _seqLen);
    }

    void playNotes(const uint8_t *data, size_t len)
    {
        printf("playnotes funcion\r\n");

        for (size_t i = 0; i < len; i++) {
            float freq = noteToFreq(data[i]);
            printf("  Note %u -> %.2f Hz\r\n", data[i], freq);

            buzzer.period(1.0f / freq);
            buzzer.write(0.5f);
            if (data[i] % 3 == 0) {
                red=1;
            } else if (data[i] % 2  == 0) {
                green=1;
            } else {
                yellow=1;
            }

            // Generate a random delay between 50ms and 400ms
            int randomDelay = rand() % 351 + 50;  // Random number between 50 and 400

            ThisThread::sleep_for(randomDelay);  // Delay based on random value

            buzzer.write(0.0f);


            // Generate another random delay for the silence
            randomDelay = rand() % 351 + 50;

            ThisThread::sleep_for(randomDelay);  // Delay based on random value
            yellow = 0;
            red = 0;
            green = 0;
        }
    }

    float noteToFreq(uint8_t note)
    {
        const float cFreq = 261.63f; // MIDI 60
        int offset = note - 60;
        return cFreq * powf(2.0f, offset / 12.0f);
    }

    // =============== Private Members ===============

    GattService        _note_service;
    GattCharacteristic _note_char;
    GattCharacteristic *_note_char_array[1];

    ble::GattServer   *_server = nullptr;
    events::EventQueue *_event_queue = nullptr;

    // Sequence storage
    uint8_t _noteBuffer[BUFFER_SIZE] = {0}; // for the characteristic value
    uint8_t _seqData[BUFFER_SIZE]    = {0}; // user data buffer
    size_t  _seqLen                  = 0;   // actual data length
};

// main.cpp
int main()
{
    mbed_trace_init();
    BLE &ble = BLE::Instance();
    events::EventQueue event_queue;
    NoteService noteService;

    GattServerProcess bleProcess(event_queue, ble);
    bleProcess.on_init(callback(&noteService, &NoteService::start));
    bleProcess.start();

    return 0;
}
