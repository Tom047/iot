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

PwmOut buzzer(D6);

class NoteService : public ble::GattServer::EventHandler {
public:
    static constexpr size_t BUFFER_SIZE = 20;

    // Updated properties to include NOTIFY
    // (WRITE | WRITE_WITHOUT_RESPONSE | NOTIFY)
    NoteService() :
            _noteChar(
                    /* Char UUID */ "485f4145-52b9-4644-af1f-7a6b9322490f",
                                    _noteBuffer,
                                    BUFFER_SIZE,
                                    BUFFER_SIZE,
                                    GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_WRITE |
                                    GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_WRITE_WITHOUT_RESPONSE |
                                    GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_NOTIFY
            ),
            _noteService(
                    "51311102-030e-485f-b122-f8f381aa84ed",
                    _noteCharArray,
                    1
            )
    {
        _noteCharArray[0] = &_noteChar;
        _noteChar.setWriteAuthorizationCallback(this, &NoteService::authorizeWrite);
    }

    void start(BLE &ble, events::EventQueue &event_queue)
    {
        _server = &ble.gattServer();
        _event_queue = &event_queue;

        printf("Registering NoteService...\r\n");
        ble_error_t err = _server->addService(_noteService);
        if (err) {
            printf("Error %u during addService.\r\n", err);
            return;
        }
        _server->setEventHandler(this);
        printf("NoteService registered.\r\n");

        // Replay stored notes every 2 seconds
        _event_queue->call_every(2s, callback(this, &NoteService::periodicPlayback));
    }

private:
    // =============== BLE GATT Event Handling ===============

    void onDataWritten(const GattWriteCallbackParams &params) override
    {
        if (params.handle == _noteChar.getValueHandle()) {
            printf("Note data received (len=%u): ", params.len);
            for (size_t i = 0; i < params.len; i++) {
                printf("%02X ", params.data[i]);
            }
            printf("\r\n");

            // Store data for future playback
            _seqLen = params.len > BUFFER_SIZE ? BUFFER_SIZE : params.len;
            memcpy(_seqData, params.data, _seqLen);
        }
    }

    // For demonstration: We'll notify the phone each time we begin playback
    void onDataSent(const GattDataSentCallbackParams &params) override
    {
        // If we used notify below, we get here after sending
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

    void periodicPlayback()
    {
        if (_seqLen == 0) {
            return; // no notes
        }
        printf("Playing stored sequence (len=%u)\r\n", _seqLen);

        // **Send a notification** to the phone that we are about to play
        // The data can be anything, e.g. a timestamp or "playing" info
        sendNotifyPayload();

        // Actually play the notes
        playNotes(_seqData, _seqLen);
    }

    void playNotes(const uint8_t *data, size_t len)
    {
        for (size_t i = 0; i < len; i++) {
            float freq = noteToFreq(data[i]);
            printf("  Note %u -> %.2f Hz\r\n", data[i], freq);

            buzzer.period(1.0f / freq);
            buzzer.write(0.5f);
            ThisThread::sleep_for(200ms);

            buzzer.write(0.0f);
            ThisThread::sleep_for(50ms);
        }
    }

    float noteToFreq(uint8_t note)
    {
        const float cFreq = 261.63f; // MIDI 60
        int offset = note - 60;
        return cFreq * powf(2.0f, offset / 12.0f);
    }

    // Send a small notify with dummy data (e.g. 0x01).
    void sendNotifyPayload()
    {
        // If no clients have subscribed, GattServer::write() returns an error
        // but that’s harmless. We'll try to notify anyway.
        const uint8_t dummyData[1] = { 0x01 };

        ble_error_t err = _server->write(
                _noteChar.getValueHandle(),
                dummyData,
                sizeof(dummyData),
                false // localOnly=false => notify subscribed clients
        );

        if (err) {
            printf("Notify write error: %u\r\n", err);
        }
    }

    // =============== Private Members ===============
    static constexpr size_t BUFFER_SIZE = 20;
    GattCharacteristic _noteChar;
    GattCharacteristic *_noteCharArray[1];
    GattService        _noteService;

    ble::GattServer   *_server = nullptr;
    events::EventQueue *_event_queue = nullptr;

    // Sequence buffer
    uint8_t _noteBuffer[BUFFER_SIZE] = {0};
    uint8_t _seqData[BUFFER_SIZE]    = {0};
    size_t  _seqLen                  = 0;
};

// main.cpp
#include "mbed.h"

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
